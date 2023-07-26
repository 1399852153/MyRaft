package myraft.module;

import myraft.RaftServer;
import myraft.api.command.Command;
import myraft.api.model.AppendEntriesRpcParam;
import myraft.api.model.AppendEntriesRpcResult;
import myraft.api.model.LogEntry;
import myraft.api.service.RaftService;
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
    }

    public void writeLocalLog(LogEntry LogEntry){
        writeLocalLog(Collections.singletonList(LogEntry),this.lastIndex);
    }

    public void writeLocalLog(List<LogEntry> logEntryList){
        writeLocalLog(logEntryList,this.lastIndex);
    }

    /**
     * 按照顺序追加写入日志
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

        long offset = localLogEntry.getOffset();

        try(RandomAccessFile randomAccessFile = new RandomAccessFile(logFile,"rw")){

            for(LogEntry logEntryItem : logEntryList){
                // 在offset指定的位置后面追加写入
                randomAccessFile.seek(offset);

                writeLog(randomAccessFile,logEntryItem);

                randomAccessFile.writeLong(offset);

                // 更新偏移量
                offset = randomAccessFile.getFilePointer();

                // 持久化currentOffset的值，二阶段提交修改currentOffset的值，宕机恢复时以持久化的值为准
                refreshMetadata(this.logMetaDataFile,offset);
            }

            // 写完了这一批日志，刷新currentOffset
            this.currentOffset = randomAccessFile.getFilePointer();
            // 设置最后写入的索引编号，lastIndex
            this.lastIndex = logEntryList.get(logEntryList.size()-1).getLogIndex();
        } catch (IOException e) {
            throw new MyRaftException("logModule writeLog error!",e);
        } finally {
            writeLock.unlock();
        }
    }

    private static void writeLog(RandomAccessFile randomAccessFile, LogEntry logEntry) throws IOException {
        randomAccessFile.writeLong(logEntry.getLogIndex());
        randomAccessFile.writeInt(logEntry.getLogTerm());

        byte[] commandBytes = JsonUtil.obj2Str(logEntry.getCommand()).getBytes(StandardCharsets.UTF_8);
        randomAccessFile.writeInt(commandBytes.length);
        randomAccessFile.write(commandBytes);
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

                    // preLogOffset
                    offset = localLogEntry.getOffset();
                    if (offset < LONG_SIZE) {
                        // 整个文件都读完了
                        return logEntryList;
                    }

                    // 跳转到记录的offset处
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

                    // 要发送的日志起始值(基于appendLogEntryBatchNum获取需要发送的日志)
                    long logIndexStart = nextIndex - appendLogEntryBatchNum;
                    // 读取出[logIndexStart,nextIndex]的日志(左闭右闭区间)
                    List<LocalLogEntry> LocalLogEntryList = this.readLocalLog(logIndexStart,nextIndex);
                    List<LogEntry> logEntryList = LocalLogEntryList.stream().map(item-> (LogEntry)item).collect(Collectors.toList());
                    if(logEntryList.size() == appendLogEntryBatchNum + 1){
                        // 一般情况能查出appendLogEntryBatchNum+1条日志，第一条(logIndexStart)用于设置prev相关参数
                        LogEntry preLogEntry = logEntryList.get(0);

                        appendEntriesRpcParam.setEntries(logEntryList);
                        appendEntriesRpcParam.setPrevLogIndex(preLogEntry.getLogIndex());
                        appendEntriesRpcParam.setPrevLogTerm(preLogEntry.getLogTerm());
                    }else if(logEntryList.size() > 0 && logEntryList.size() <= appendLogEntryBatchNum){
                        // 日志长度小于appendLogEntryBatchNum+1，说明最前面的是第一条记录(比如appendLogEntryBatchNum=5，但一共只有3条日志全查出来了)
                        appendEntriesRpcParam.setEntries(logEntryList);

                        // 约定好第一条记录的prev的index和term都是-1
                        appendEntriesRpcParam.setPrevLogIndex(-1);
                        appendEntriesRpcParam.setPrevLogTerm(-1);
                    } else{
                        // 正常情况是先持久化然后再广播同步日志，所以肯定有
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
                        // 同步成功了，nextIndex递增一位

                        // If successful: update nextIndex and matchIndex for follower (§5.3)

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
                this.rpcThreadPool,3, TimeUnit.SECONDS);

        logger.info("leader replicationLogEntry appendEntriesRpcResultList={}",appendEntriesRpcResultList);

        return appendEntriesRpcResultList;
    }

    public LogEntry getLastLogEntry(){
        return readLocalLog(this.lastIndex);
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

        logEntry.setOffset(randomAccessFile.readLong());
        return logEntry;
    }

    private static void refreshMetadata(File logMetaDataFile,long currentOffset) throws IOException {
        try(RandomAccessFile randomAccessFile = new RandomAccessFile(logMetaDataFile,"rw")){
            randomAccessFile.seek(0);
            randomAccessFile.writeLong(currentOffset);
        }
    }

    private String getLogFileDir(){
        return System.getProperty("user.dir")
            + File.separator + currentServer.getServerId();
    }
}
