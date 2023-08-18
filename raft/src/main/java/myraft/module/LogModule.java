package myraft.module;

import myraft.RaftServer;
import myraft.api.command.Command;
import myraft.api.model.*;
import myraft.api.service.RaftService;
import myraft.common.model.RaftSnapshot;
import myraft.exception.MyRaftException;
import myraft.module.model.LocalLogEntry;
import myraft.util.util.CommonUtil;
import myraft.util.util.MyRaftFileUtil;
import myrpc.common.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

public class LogModule {

    private static final Logger logger = LoggerFactory.getLogger(LogModule.class);

    private static final int LONG_SIZE = 8;

    private final File logFile;
    private final File logMetaDataFile;

    /**
     * 每条记录后面都带上当时的currentOffset，用于找到对应记录（currentOffset用于持久化）
     * */
    private volatile long currentOffset;

    /**
     * 已写入的当前日志索引号
     * */
    private volatile long lastIndex;

    /**
     * 已提交的最大日志索引号（论文中的commitIndex）
     * rpc复制到多数节点上，日志就认为是已提交
     * */
    private volatile long lastCommittedIndex = -1;

    /**
     * 作用到状态机上，日志就认为是已应用
     * */
    private volatile long lastApplied = -1;

    private final ExecutorService rpcThreadPool;

    private final RaftServer currentServer;

    private final ReentrantReadWriteLock reentrantLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = reentrantLock.writeLock();
    private final ReentrantReadWriteLock.ReadLock readLock = reentrantLock.readLock();

    private static final String logFileName = "raftLog.txt";
    private static final String logMetaDataFileName = "raftLogMeta.txt";
    private static final String logTempFileName = "raftLog-temp.txt";
    private static final String logMetaDataTempFileName = "raftLogMeta-temp.txt";

    public LogModule(RaftServer currentServer) throws IOException {
        this.currentServer = currentServer;

        int threadPoolSize = Math.max(currentServer.getOtherNodeInCluster().size(),1) * 2;
        this.rpcThreadPool = new ThreadPoolExecutor(threadPoolSize, threadPoolSize * 2,
            0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>());

        String logFileDir = getLogFileDir();

        this.logMetaDataFile = new File(logFileDir + File.separator + logMetaDataFileName);
        MyRaftFileUtil.createFile(logMetaDataFile);

        this.logFile = new File(logFileDir + File.separator + logFileName);
        MyRaftFileUtil.createFile(logFile);

        try(RandomAccessFile randomAccessLogMetaDataFile = new RandomAccessFile(logMetaDataFile, "r")) {
            if (randomAccessLogMetaDataFile.length() >= LONG_SIZE) {
                this.currentOffset = randomAccessLogMetaDataFile.readLong();
            } else {
                this.currentOffset = 0;
            }
        }

        try(RandomAccessFile randomAccessLogFile = new RandomAccessFile(logFile,"r")) {
            // 尝试读取之前已有的日志文件，找到最后一条日志的index
            if (this.currentOffset >= LONG_SIZE) {
                // 跳转到最后一个记录的offset处
                randomAccessLogFile.seek(this.currentOffset - LONG_SIZE);

                // 获得记录的offset
                long entryOffset = randomAccessLogFile.readLong();
                // 跳转至对应位置
                randomAccessLogFile.seek(entryOffset);

                this.lastIndex = randomAccessLogFile.readLong();
            }else{
                // 没有历史的日志
                this.lastIndex = -1;
            }
        }

        logger.info("logModule init success, lastIndex={}",lastIndex);
    }

    public void writeLocalLog(LogEntry LogEntry){
        writeLocalLog(Collections.singletonList(LogEntry),this.lastIndex);
    }

    public void writeLocalLog(List<LogEntry> logEntryList){
        writeLocalLog(logEntryList,this.lastIndex);
    }

    /**
     * 在logIndex后覆盖写入日志
     * */
    public void writeLocalLog(List<LogEntry> logEntryList, long logIndex){
        if(logEntryList.isEmpty()){
            return;
        }

        boolean lockSuccess = writeLock.tryLock();
        if(!lockSuccess){
            logger.error("writeLocalLog lock error!");
            return;
        }

        // 找到标记点
        LocalLogEntry localLogEntry = readLocalLog(logIndex);
        if(localLogEntry == null){
            localLogEntry = LocalLogEntry.getEmptyLogEntry();
        }

        long offset = localLogEntry.getEndOffset();

        try(RandomAccessFile randomAccessFile = new RandomAccessFile(logFile,"rw")){

            for(LogEntry logEntryItem : logEntryList){
                // 在offset指定的位置后面追加写入
                randomAccessFile.seek(offset);
                // 向本地文件中写入日志的内容
                writeLog(randomAccessFile,logEntryItem,offset);

                // 更新偏移量
                offset = randomAccessFile.getFilePointer();

                // 持久化currentOffset的值，二阶段提交修改currentOffset的值，宕机恢复时以持久化的值为准
                refreshMetadata(this.logMetaDataFile,offset);
            }

            // 写完了这一批日志，刷新currentOffset
            this.currentOffset = offset;
            // 设置最后写入的索引编号，lastIndex
            this.lastIndex = logEntryList.get(logEntryList.size()-1).getLogIndex();
        } catch (IOException e) {
            throw new MyRaftException("logModule writeLog error!",e);
        } finally {
            writeLock.unlock();
        }
    }

    private static void writeLog(RandomAccessFile randomAccessFile, LogEntry logEntry, long startOffset) throws IOException {
        randomAccessFile.writeLong(logEntry.getLogIndex());
        randomAccessFile.writeInt(logEntry.getLogTerm());

        byte[] commandBytes = JsonUtil.obj2Str(logEntry.getCommand()).getBytes(StandardCharsets.UTF_8);
        randomAccessFile.writeInt(commandBytes.length);
        randomAccessFile.write(commandBytes);

        // 写入当前日志起始的偏移值（LocalLogEntry.startOffset）
        randomAccessFile.writeLong(startOffset);
    }

    /**
     * 根据日志索引号，获得对应的日志记录
     * */
    public LocalLogEntry readLocalLog(long logIndex) {
        readLock.lock();

        try {
            List<LocalLogEntry> logEntryList = readLocalLogNoSort(logIndex, logIndex);
            if (logEntryList.isEmpty()) {
                return null;
            } else {
                // 只会有1个
                return logEntryList.get(0);
            }
        }finally {
            readLock.unlock();
        }
    }

    /**
     * 根据日志索引号，获得对应的日志记录
     * 左右闭区间（logIndexStart <= {index} <= logIndexEnd）
     * */
    public List<LocalLogEntry> readLocalLog(long logIndexStart, long logIndexEnd) {
        readLock.lock();

        try {
            // 读取出来的时候是index从大到小排列的
            List<LocalLogEntry> logEntryList = readLocalLogNoSort(logIndexStart, logIndexEnd);

            // 翻转一下，令其按index从小到大排列
            Collections.reverse(logEntryList);

            return logEntryList;
        }finally {
            readLock.unlock();
        }
    }

    /**
     * 根据日志索引号，获得对应的日志记录
     * 左右闭区间（logIndexStart <= {index} <= logIndexEnd）
     * */
    private List<LocalLogEntry> readLocalLogNoSort(long logIndexStart, long logIndexEnd) {
        if(logIndexStart > logIndexEnd){
            throw new MyRaftException("readLocalLog logIndexStart > logIndexEnd! " +
                "logIndexStart=" + logIndexStart + " logIndexEnd=" + logIndexEnd);
        }

        boolean lockSuccess = readLock.tryLock();
        if(!lockSuccess){
            throw new MyRaftException("readLocalLogNoSort lock error!");
        }

        try {
            List<LocalLogEntry> logEntryList = new ArrayList<>();
            try (RandomAccessFile randomAccessFile = new RandomAccessFile(this.logFile, "r")) {
                // 从后往前找
                long offset = this.currentOffset;

                if (offset >= LONG_SIZE) {
                    // 跳转到最后一个记录的offset处
                    randomAccessFile.seek(offset - LONG_SIZE);
                }

                while (offset > 0) {
                    // 获得记录的offset
                    long entryOffset = randomAccessFile.readLong();
                    // 跳转至对应位置
                    randomAccessFile.seek(entryOffset);

                    long targetLogIndex = randomAccessFile.readLong();
                    if (targetLogIndex < logIndexStart) {
                        // 从下向上找到的顺序，如果已经小于参数指定的了，说明日志里根本就没有需要的日志条目，直接返回null
                        return logEntryList;
                    }

                    LocalLogEntry localLogEntry = readLocalLogByOffset(randomAccessFile, targetLogIndex);
                    if (targetLogIndex <= logIndexEnd) {
                        // 找到的符合要求
                        logEntryList.add(localLogEntry);
                    }

                    offset = localLogEntry.getStartOffset();
                    if (offset < LONG_SIZE) {
                        // 整个文件都读完了
                        return logEntryList;
                    }

                    // 跳转到上一条记录的offset处
                    randomAccessFile.seek(offset - LONG_SIZE);
                }
            } catch (IOException e) {
                throw new MyRaftException("logModule readLog error!", e);
            }

            // 找遍了整个文件，也没找到，返回null
            return logEntryList;
        }finally {
            readLock.unlock();
        }
    }

    /**
     * 删除包括logIndex以及更大序号的所有日志
     * */
    public void deleteLocalLog(long logIndexNeedDelete){
        // 已经确认提交的日志不能删除
        if(logIndexNeedDelete <= this.lastCommittedIndex){
            throw new MyRaftException("can not delete committed log! " +
                "logIndexNeedDelete=" + logIndexNeedDelete + ",lastCommittedIndex=" + this.lastIndex);
        }

        boolean lockSuccess = writeLock.tryLock();
        if(!lockSuccess){
            logger.error("deleteLocalLog lock error!");
            return;
        }

        try(RandomAccessFile randomAccessFile = new RandomAccessFile(this.logFile,"r")) {
            // 从后往前找
            long offset = this.currentOffset;

            if(offset >= LONG_SIZE) {
                // 跳转到最后一个记录的offset处
                randomAccessFile.seek(offset - LONG_SIZE);
            }

            while (offset > 0) {
                // 获得记录的offset
                long entryOffset = randomAccessFile.readLong();
                // 跳转至对应位置
                randomAccessFile.seek(entryOffset);

                long targetLogIndex = randomAccessFile.readLong();
                if(targetLogIndex < logIndexNeedDelete){
                    // 从下向上找到的顺序，如果已经小于参数指定的了，说明日志里根本就没有需要删除的日志条目，直接返回
                    return;
                }

                // 找到了对应的日志条目
                if(targetLogIndex == logIndexNeedDelete){
                    // 把文件的偏移量刷新一下就行(相当于逻辑删除这条日志以及之后的entry)
                    this.currentOffset = entryOffset;
                    refreshMetadata(this.logMetaDataFile,this.currentOffset);
                    return;
                }else{
                    // 没找到

                    // 跳过当前日志的剩余部分，继续向上找
                    randomAccessFile.readInt();
                    int commandLength = randomAccessFile.readInt();
                    randomAccessFile.read(new byte[commandLength]);

                    // preLogOffset
                    offset = randomAccessFile.readLong();
                    // 跳转到记录的offset处
                    randomAccessFile.seek(offset - LONG_SIZE);
                }
            }

            this.lastIndex = logIndexNeedDelete - 1;
        } catch (IOException e) {
            throw new MyRaftException("logModule deleteLog error!",e);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 向集群广播，令follower复制新的日志条目
     * */
    public List<AppendEntriesRpcResult> replicationLogEntry(LogEntry lastEntry) {
        List<RaftService> otherNodeInCluster = currentServer.getOtherNodeInCluster();

        List<Future<AppendEntriesRpcResult>> futureList = new ArrayList<>(otherNodeInCluster.size());

        for(RaftService node : otherNodeInCluster){
            // 并行发送rpc，要求follower复制日志
            Future<AppendEntriesRpcResult> future = this.rpcThreadPool.submit(()->{
                logger.info("replicationLogEntry start!");

                long nextIndex = this.currentServer.getNextIndexMap().get(node);

                AppendEntriesRpcResult finallyResult = null;

                // If last log index ≥ nextIndex for a follower: send AppendEntries RPC with log entries starting at nextIndex
                while(lastEntry.getLogIndex() >= nextIndex){
                    AppendEntriesRpcParam appendEntriesRpcParam = new AppendEntriesRpcParam();
                    appendEntriesRpcParam.setLeaderId(currentServer.getServerId());
                    appendEntriesRpcParam.setTerm(currentServer.getCurrentTerm());
                    appendEntriesRpcParam.setLeaderCommit(this.lastCommittedIndex);

                    int appendLogEntryBatchNum = this.currentServer.getRaftConfig().getAppendLogEntryBatchNum();

                    // 要发送的日志最大index值
                    // (追进度的时候，就是nextIndex开始批量发送appendLogEntryBatchNum-1条(左闭右闭区间)；如果进度差不多那就是以lastEntry.index为界限全部发送出去)
                    long logIndexEnd = Math.min(nextIndex+(appendLogEntryBatchNum-1), lastEntry.getLogIndex());
                    // 读取出[nextIndex-1,logIndexEnd]的日志(左闭右闭区间),-1往前一位是为了读取出preLog的信息
                    List<LocalLogEntry> localLogEntryList = this.readLocalLog(nextIndex-1,logIndexEnd);

                    logger.info("replicationLogEntry doing! nextIndex={},logIndexEnd={},LocalLogEntryList={}",
                        nextIndex,logIndexEnd,JsonUtil.obj2Str(localLogEntryList));

                    List<LogEntry> logEntryList = localLogEntryList.stream()
                        .map(LogEntry::toLogEntry)
                        .collect(Collectors.toList());

                    // 索引区间大小
                    long indexRange = (logIndexEnd - nextIndex + 1);
                    if(logEntryList.size() == indexRange+1){
                        // 一般情况能查出区间内的所有日志

                        logger.info("find log size match!");
                        // preLog
                        LogEntry preLogEntry = logEntryList.get(0);
                        // 实际需要传输的log
                        List<LogEntry> needAppendLogList = logEntryList.subList(1,logEntryList.size());
                        appendEntriesRpcParam.setEntries(needAppendLogList);
                        appendEntriesRpcParam.setPrevLogIndex(preLogEntry.getLogIndex());
                        appendEntriesRpcParam.setPrevLogTerm(preLogEntry.getLogTerm());
                    }else if(logEntryList.size() > 0 && logEntryList.size() <= indexRange){
                        // todo 新增日志压缩功能后，查出来的数据个数小于指定的区间不一定就是查到第一条数据，还有可能是日志被压缩了

                        logger.info("find log size not match!");
                        // 日志长度小于索引区间值，说明已经查到最前面的日志 (比如appendLogEntryBatchNum=5，但一共只有3条日志全查出来了)
                        appendEntriesRpcParam.setEntries(logEntryList);

                        // 约定好第一条记录的prev的index和term都是-1
                        appendEntriesRpcParam.setPrevLogIndex(-1);
                        appendEntriesRpcParam.setPrevLogTerm(-1);
                    } else{
                        // 正常情况是先持久化然后再广播同步日志，所以size肯定会大于0，也不应该超过索引区间值
                        // 走到这里不符合预期，日志模块有bug
                        throw new MyRaftException("replicationLogEntry logEntryList size error!" +
                            " nextIndex=" + nextIndex + " logEntryList.size=" + logEntryList.size());
                    }

                    logger.info("leader do appendEntries start, node={}, appendEntriesRpcParam={}",node,appendEntriesRpcParam);
                    AppendEntriesRpcResult appendEntriesRpcResult = node.appendEntries(appendEntriesRpcParam);
                    logger.info("leader do appendEntries end, node={}, appendEntriesRpcResult={}",node,appendEntriesRpcResult);

                    finallyResult = appendEntriesRpcResult;
                    // 收到更高任期的处理
                    boolean beFollower = currentServer.processCommunicationHigherTerm(appendEntriesRpcResult.getTerm());
                    if(beFollower){
                        return appendEntriesRpcResult;
                    }

                    if(appendEntriesRpcResult.isSuccess()){
                        logger.info("appendEntriesRpcResult is success, node={}",node);

                        // If successful: update nextIndex and matchIndex for follower (§5.3)

                        // 同步成功了，nextIndex递增一位
                        this.currentServer.getNextIndexMap().put(node,nextIndex+1);
                        this.currentServer.getMatchIndexMap().put(node,nextIndex);

                        nextIndex++;
                    }else{
                        // 因为日志对不上导致一致性检查没通过，同步没成功，nextIndex往后退一位

                        logger.info("appendEntriesRpcResult is false, node={}",node);

                        // If AppendEntries fails because of log inconsistency: decrement nextIndex and retry (§5.3)
                        nextIndex--;
                        this.currentServer.getNextIndexMap().put(node,nextIndex);
                    }
                }

                if(finallyResult == null){
                    // 说明有bug
                    throw new MyRaftException("replicationLogEntry finallyResult is null!");
                }

                logger.info("finallyResult={},node={}",node,finallyResult);

                return finallyResult;
            });

            futureList.add(future);
        }

        // 获得结果
        List<AppendEntriesRpcResult> appendEntriesRpcResultList = CommonUtil.concurrentGetRpcFutureResult(
                "do appendEntries", futureList,
                this.rpcThreadPool,2, TimeUnit.SECONDS);

        logger.info("leader replicationLogEntry appendEntriesRpcResultList={}",appendEntriesRpcResultList);

        return appendEntriesRpcResultList;
    }

    public LogEntry getLastLogEntry(){
        LogEntry lastLogEntry = readLocalLog(this.lastIndex);
        if(lastLogEntry != null){
            return lastLogEntry;
        }else {
            return LogEntry.getEmptyLogEntry();
        }
    }

    /**
     * discard any existing or partial snapshot with a smaller index
     * 快照整体安装完毕，清理掉index小于等于快照中lastIncludedIndex的所有日志
     * */
    public void compressLogBySnapshot(InstallSnapshotRpcParam installSnapshotRpcParam){
        this.lastCommittedIndex = installSnapshotRpcParam.getLastIncludedIndex();
        if(this.lastIndex < this.lastCommittedIndex){
            this.lastIndex = this.lastCommittedIndex;
        }

        try {
            buildNewLogFileRemoveCommittedLog();
        } catch (IOException e) {
            throw new MyRaftException("compressLogBySnapshot error",e);
        }
    }

    public static void doInstallSnapshotRpc(RaftService targetNode, RaftSnapshot raftSnapshot, RaftServer currentServer){
        int installSnapshotBlockSize = currentServer.getRaftConfig().getInstallSnapshotBlockSize();
        byte[] completeSnapshotData = raftSnapshot.getSnapshotData();

        int currentOffset = 0;
        while(true){
            InstallSnapshotRpcParam installSnapshotRpcParam = new InstallSnapshotRpcParam();
            installSnapshotRpcParam.setLastIncludedIndex(raftSnapshot.getLastIncludedIndex());
            installSnapshotRpcParam.setTerm(currentServer.getCurrentTerm());
            installSnapshotRpcParam.setLeaderId(currentServer.getServerId());
            installSnapshotRpcParam.setLastIncludedTerm(raftSnapshot.getLastIncludedTerm());
            installSnapshotRpcParam.setOffset(currentOffset);

            // 填充每次传输的数据块
            int blockSize = Math.min(installSnapshotBlockSize,completeSnapshotData.length-currentOffset);
            byte[] block = new byte[blockSize];
            System.arraycopy(completeSnapshotData,currentOffset,block,0,blockSize);
            installSnapshotRpcParam.setData(block);

            currentOffset += installSnapshotBlockSize;
            if(currentOffset >= completeSnapshotData.length){
                installSnapshotRpcParam.setDone(true);
            }else{
                installSnapshotRpcParam.setDone(false);
            }

            InstallSnapshotRpcResult installSnapshotRpcResult = targetNode.installSnapshot(installSnapshotRpcParam);

            boolean beFollower = currentServer.processCommunicationHigherTerm(installSnapshotRpcResult.getTerm());
            if(beFollower){
                // 传输过程中发现自己已经不再是leader了，快速结束
                logger.info("doInstallSnapshotRpc beFollower quick return!");
                return;
            }

            if(installSnapshotRpcParam.isDone()){
                logger.info("doInstallSnapshotRpc isDone!");
                return;
            }
        }
    }

    // ============================= get/set ========================================

    public long getLastIndex() {
        return lastIndex;
    }

    public long getLastCommittedIndex() {
        return lastCommittedIndex;
    }

    public void setLastCommittedIndex(long lastCommittedIndex) {
        writeLock.lock();

        try {
            if (lastCommittedIndex < this.lastCommittedIndex) {
                throw new MyRaftException("set lastCommittedIndex error this.lastCommittedIndex=" + this.lastCommittedIndex
                    + " lastCommittedIndex=" + lastCommittedIndex);
            }

            this.lastCommittedIndex = lastCommittedIndex;
        }finally {
            writeLock.unlock();
        }
    }

    public long getLastApplied() {
        return lastApplied;
    }

    public void setLastApplied(long lastApplied) {
        writeLock.lock();

        try {
            if (lastApplied < this.lastApplied) {
                throw new MyRaftException("set lastApplied error this.lastApplied=" + this.lastApplied
                    + " lastApplied=" + lastApplied);
            }

            this.lastApplied = lastApplied;
        }finally {
            writeLock.unlock();
        }
    }

    /**
     * 用于单元测试
     * */
    public void clean() {
        System.out.println("log module clean!");
        this.logFile.delete();
        this.logMetaDataFile.delete();
    }

    private LocalLogEntry readLocalLogByOffset(RandomAccessFile randomAccessFile, long logIndex) throws IOException {
        LocalLogEntry logEntry = new LocalLogEntry();
        logEntry.setLogIndex(logIndex);
        logEntry.setLogTerm(randomAccessFile.readInt());

        int commandLength = randomAccessFile.readInt();
        byte[] commandBytes = new byte[commandLength];
        randomAccessFile.read(commandBytes);

        String jsonStr = new String(commandBytes,StandardCharsets.UTF_8);
        Command command = JsonUtil.json2Obj(jsonStr, Command.class);
        logEntry.setCommand(command);

        // 本条日志位于[startOffset,endOffset)之间，左闭右开
        logEntry.setStartOffset(randomAccessFile.readLong());
        logEntry.setEndOffset(randomAccessFile.getFilePointer());
        return logEntry;
    }

    private static void refreshMetadata(File logMetaDataFile,long currentOffset) throws IOException {
        logger.info("refreshMetadata currentOffset={}",currentOffset);
        try(RandomAccessFile randomAccessFile = new RandomAccessFile(logMetaDataFile,"rw")){
            randomAccessFile.seek(0);
            randomAccessFile.writeLong(currentOffset);
        }
    }

    private String getLogFileDir(){
        return System.getProperty("user.dir")
            + File.separator + currentServer.getServerId();
    }

    /**
     * 构建一个删除了已提交日志的新日志文件(日志压缩到快照里了)
     * */
    private void buildNewLogFileRemoveCommittedLog() throws IOException {
        long lastCommitted = getLastCommittedIndex();
        long lastIndex = getLastIndex();

        // 暂不考虑读取太多造成内存溢出的问题
        List<LocalLogEntry> logEntryList;
        if(lastCommitted == lastIndex){
            // (lastCommitted == lastIndex) 所有日志都提交了，创建一个空的新日志文件
            logEntryList = new ArrayList<>();
        }else{
            // 还有日志没提交，把没提交的记录到新的日志文件中
            logEntryList = readLocalLog(lastCommitted+1,lastIndex);
        }

        File tempLogFile = new File(getLogFileDir() + File.separator + logTempFileName);
        MyRaftFileUtil.createFile(tempLogFile);
        try(RandomAccessFile randomAccessTempLogFile = new RandomAccessFile(tempLogFile,"rw")) {

            long currentOffset = 0;
            for (LogEntry logEntry : logEntryList) {
                // 写入日志
                writeLog(randomAccessTempLogFile, logEntry, currentOffset);

                currentOffset = randomAccessTempLogFile.getFilePointer();
            }

            this.currentOffset = currentOffset;
        }

        File tempLogMeteDataFile = new File(getLogFileDir() + File.separator + logMetaDataTempFileName);
        MyRaftFileUtil.createFile(tempLogMeteDataFile);

        // 临时的日志元数据文件写入数据
        refreshMetadata(tempLogMeteDataFile,currentOffset);

        writeLock.lock();
        try{
            // 先删掉原来的日志文件，然后把临时文件重名名为日志文件(delete后、重命名前可能宕机，但是没关系，重启后构造方法里做了对应处理)
            this.logFile.delete();
            boolean renameLogFileResult = tempLogFile.renameTo(this.logFile);
            if(!renameLogFileResult){
                logger.error("renameLogFile error!");
            }

            // 先删掉原来的日志元数据文件，然后把临时文件重名名为日志元数据文件(delete后、重命名前可能宕机，但是没关系，重启后构造方法里做了对应处理)
            this.logMetaDataFile.delete();
            boolean renameTempLogMeteDataFileResult = tempLogMeteDataFile.renameTo(this.logMetaDataFile);
            if(!renameTempLogMeteDataFileResult){
                logger.error("renameTempLogMeteDataFile error!");
            }
        }finally {
            writeLock.unlock();
        }
    }
}
