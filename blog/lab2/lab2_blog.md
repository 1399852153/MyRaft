# 手写raft(二) 实现日志复制
## 1. Raft日志复制介绍
在上一篇博客中MyRaft实现了leader选举，为实现日志复制功能打下了基础: [手写raft(一) 实现leader选举](https://www.cnblogs.com/xiaoxiongcanguan/p/17569697.html)  
#####
日志复制是raft最核心也是最复杂的功能，大体上来说一次正常的raft日志复制大致可以简化为以下几步完成：  
1. 客户端向raft集群发送一次操作请求(比如kv数据库状态机的写命令(set k1 v1))，如果接受到请求的节点是leader则受理该请求；
   如果不是leader则转发给自己认为的leader或者返回leader的地址让客户端向leader重新发送请求(如果过程中小概率发生了leader变更，则循环往复直到leader受理)
2. leader在接受到请求后，生成对应的raft日志(logEntry)并追加写入到leader节点本地的日志文件中(整个过程需要加锁，防止多个请求并发而日志乱序)。  
3. leader本地日志追加写入完成后，向集群中的所有follower节点广播该日志(并行的rpc appendEntries)，follower接到该rpc请求后也将日志追加写入到自己的本地日志文件中，并返回成功。  
   当超过半数的follower返回了成功时，leader认为该日志已经成功的复制到了集群中，可以提交到状态机中执行。  
   如果成功的数量少于半数则认为该客户端请求失败，不提交到状态机中，并将本地写入的日志删除掉，让客户端去重试。
4. leader提交日志给状态机后，会修改自己的lastApplied字段(最大已提交日志索引编号)，随后通过心跳等rpc交互令follower也提交本地对应索引的日志到状态机中
5. 至此，一次完整的日志复制过程完成，一切正常的情况下整个集群中所有节点的本地日志中都包含了对应请求的日志，每个节点的状态机也执行了对应日志包含的状态机指令。
#####
* 上面关于raft日志复制功能的介绍看起来不算复杂，在一切正常的情况下系统似乎能很好的完成任务。但raft是一个分布式系统，分布式系统中会出现各种麻烦的异常情况，在上述任务的每一步、任一瞬间都可能出现网络故障、机器宕机(leader/follower都可能处理到一半就宕机)等问题，
而如何在异常情况下依然能让系统正常完成任务就使得逻辑变得复杂起来了。  
* raft论文中对一些异常情况进行了简要的介绍，并通过一些示例来证明算法的正确性。但具体落地到代码实现中还有更多的细节需要考虑，这也是为什么raft论文中反复强调算法易理解的重要性。
* 因为算法容易理解，实现者就能对算法建立起直观的理解，处理异常case时就能更好的理解应该如何做以及为什么这样做是正确的。在下文中将会结合MyRaft的实现源码来详细分析raft是如何处理异常情况的。
## 2. MyRaft日志复制
### 2.1 MyRaft日志复制相关模块简介
为了实现日志复制功能，lab2版本的MyRaft比起lab1额外新增了3个模块，分别是日志模块LogModule、状态机模块SimpleReplicationStateMachine和客户端模块RaftClient。  
##### 日志模块LogModule
* MyRaft的日志模块用于维护raft日志相关的逻辑，出于减少依赖的原因，MyRaft没有去依赖rocksDb等基于本地磁盘的数据库而是直接操作最原始的日志文件(RandomAccessFile)来实现日志存储功能。  
* 同时简单起见，不过多考虑性能直接通过一把全局的读写锁来防止并发问题。
```java
/**
 * raft日志条目
 * */
public class LogEntry implements Serializable {

    /**
     * 发布日志时的leader的任期编号
     * */
    private int logTerm;

    /**
     * 日志的索引编号
     * */
    private long logIndex;

    /**
     * 具体作用在状态机上的指令
     * */
    private Command command;
}
```
todo LogEntry在磁盘文件中存储的示意图
```java
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

                writeLog(randomAccessFile,logEntryItem);

                randomAccessFile.writeLong(offset);

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
    
    public LogEntry getLastLogEntry(){
        LogEntry lastLogEntry = readLocalLog(this.lastIndex);
        if(lastLogEntry != null){
            return lastLogEntry;
        }else {
            return LogEntry.getEmptyLogEntry();
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

        // 日志是位于[startOffset,endOffset)之间的，左闭右开
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
}
```
##### 状态机模块SimpleReplicationStateMachine
* 状态机模块是一个K/V数据模型，本质上就是内存中维护了一个HashMap。状态机的读写操作就是对这个HashMap的读写操作，没有额外的逻辑。
* 同时为了方便观察状态机中的数据状态，每次进行写操作时都整体刷新这个HashMap中的数据到对应的本地文件中(简单起见暂不考虑同步刷盘的性能问题)。
```java
/**
 * 指令(取决于实现)
 * */
public interface Command extends Serializable {
}

/**
 * 写操作，把一个key设置为value
 * */
public class SetCommand implements Command {

   private String key;
   private String value;
}

public class GetCommand implements Command{

   private String key;
}
```
* MyRaft是一个极简的K/V数据库，其只支持最基本的get/set命令，因此作用在状态机中的所有指令天然都是幂等的，是可重复执行的。
```java
/**
 * 简易复制状态机(持久化到磁盘中的最基础的k/v数据库)
 * 简单起见：内存中是一个k/v Map，每次写请求都全量写入磁盘
 * */
public class SimpleReplicationStateMachine implements KVReplicationStateMachine {
    private static final Logger logger = LoggerFactory.getLogger(SimpleReplicationStateMachine.class);

    private final ConcurrentHashMap<String,String> kvMap;

    private final File persistenceFile;

    private final ReentrantReadWriteLock reentrantLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = reentrantLock.writeLock();
    private final ReentrantReadWriteLock.ReadLock readLock = reentrantLock.readLock();

    public SimpleReplicationStateMachine(RaftServer raftServer){
        String userPath = System.getProperty("user.dir") + File.separator + raftServer.getServerId();

        this.persistenceFile = new File(userPath + File.separator + "raftReplicationStateMachine-" + raftServer.getServerId() + ".txt");
        MyRaftFileUtil.createFile(persistenceFile);

        // 状态机启动时不以持久化文件中的数据为准，而是等待leader的心跳执行一遍已提交的raft日志
        // 所以在这里需要清空
        kvMap = new ConcurrentHashMap<>();
        MyRaftFileUtil.writeInFile(persistenceFile, JsonUtil.obj2Str(kvMap));
    }

    @Override
    public void apply(SetCommand setCommand) {
        if(setCommand instanceof EmptySetCommand){
            // no-op，状态机无需做任何操作
            logger.info("apply EmptySetCommand quick return!");
            return;
        }

        logger.info("apply setCommand start,{}",setCommand);
        writeLock.lock();
        try {
            kvMap.put(setCommand.getKey(), setCommand.getValue());

            // 每次写操作完都持久化一遍(简单起见，暂时不考虑性能问题)
            MyRaftFileUtil.writeInFile(persistenceFile, JsonUtil.obj2Str(kvMap));
            logger.info("apply setCommand end");
        }finally {
            writeLock.unlock();
        }
    }

    @Override
    public void batchApply(List<SetCommand> setCommandList) {
        writeLock.lock();
        try{

            setCommandList = setCommandList.stream()
                // 过滤掉no-op操作
                .filter(item->!(item instanceof EmptySetCommand))
                .collect(Collectors.toList());

            logger.info("batchApply setCommand start,size={}",setCommandList.size());

            for(SetCommand setCommand : setCommandList){
                logger.info("apply setCommand start,{}",setCommand);
                kvMap.put(setCommand.getKey(), setCommand.getValue());
            }

            // 持久化(简单起见，暂时不考虑性能问题)
            MyRaftFileUtil.writeInFile(persistenceFile, JsonUtil.obj2Str(kvMap));
            logger.info("apply setCommand end");
        }finally {
            writeLock.unlock();
        }
    }

    @Override
    public String get(String key) {
        readLock.lock();

        try {
            return kvMap.get(key);
        }finally {
            readLock.unlock();
        }
    }
}

```
##### 客户端模块RaftClient
* Raft客户端模块就是一个rpc的客户端(不依赖注册中心，基于静态服务配置的点对点rpc)，请求时使用负载均衡随机的获得一个raft服务节点访问。
```java
public class RaftClient {

    private final Registry registry;
    private RaftService raftServiceProxy;
    private List<ServiceInfo> serviceInfoList;
    private final LoadBalance loadBalance = new SimpleRoundRobinBalance();

    public RaftClient(Registry registry) {
        this.registry = registry;
    }

    public void init(){
        ConsumerBootstrap consumerBootstrap = new ConsumerBootstrap()
            .registry(registry);

        // 注册消费者
        Consumer<RaftService> consumer = consumerBootstrap.registerConsumer(RaftService.class,new FastFailInvoker());
        this.raftServiceProxy = consumer.getProxy();
    }

    public void setRaftNodeConfigList(List<RaftNodeConfig> raftNodeConfigList) {
        this.serviceInfoList = raftNodeConfigList.stream().map(item->{
            ServiceInfo serviceInfo = new ServiceInfo();
            serviceInfo.setServiceName(RaftService.class.getName());
            serviceInfo.setUrlAddress(new URLAddress(item.getIp(),item.getPort()));
            return serviceInfo;
        }).collect(Collectors.toList());
    }

    public String doRequestRetry(Command command, int retryTime){
        RuntimeException ex = new RuntimeException();
        for(int i=0; i<retryTime; i++){
            try {
                return doRequest(command);
            }catch (Exception e){
                ex = new RuntimeException(e);
                System.out.println("doRequestRetry error, retryTime=" + i);
            }
        }

        // n次重试后还是没成功
        throw ex;
    }

    public String doRequest(Command command){
        // 先让负载均衡选择请求任意节点
        ServiceInfo serviceInfo = loadBalance.select(this.serviceInfoList);
        ClientRequestResult clientRequestResult = doRequest(serviceInfo.getUrlAddress(),command);

        if(clientRequestResult.getLeaderAddress() == null){
            if(!clientRequestResult.isSuccess()){
                throw new MyRpcException("doRequest error!");
            }
            // 访问到了leader，得到结果
            return clientRequestResult.getValue();
        }else{
            // leaderAddress不为空，说明访问到了follower，得到follower给出的leader地址
            URLAddress urlAddress = clientRequestResult.getLeaderAddress();
            // 指定leader的地址去发起请求
            ClientRequestResult result = doRequest(urlAddress,command);

            return result.getValue();
        }
    }

    private ClientRequestResult doRequest(URLAddress urlAddress, Command command){
        // 相当于是点对点的rpc，用这种方式比较奇怪，但可以不依赖zookeeper这样的注册中心
        ConsumerRpcContextHolder.getConsumerRpcContext().setTargetProviderAddress(urlAddress);
        ClientRequestParam clientRequestParam = new ClientRequestParam(command);
        ClientRequestResult clientRequestResult = this.raftServiceProxy.clientRequest(clientRequestParam);
        ConsumerRpcContextHolder.removeConsumerRpcContext();

        return clientRequestResult;
    }
}
```
### 2.2 MyRaft日志复制实现分析
##### MyRaft处理客户端请求
* raft是一个强一致的读写模型，因此只有leader才能对外进行服务。因此raft服务节点收到来自客户端的请求时，需要判断一下自己是否是leader，如果不是leader就返回自己认为的leader地址给客户端，让客户端重试。  
raft是一个分布式模型，在出现网络分区等情况下，原来是leader的节点(term更小的老leader)可能并不是目前真正的leader，而这个情况下接到客户端请求的老leader就会错误的处理客户端的请求。  
* 线性强一致的写：raft的leader节点在处理客户端请求时会加写锁(线性一致)。提交指令到状态机中执行前，会预先写入一份本地日志，并将本地日志广播到集群中的所有follower节点上。如果自己已经不再是合法的leader，则本地日志的广播是无法在超过半数的节点上执行成功的。
  反过来说，只要大多数的节点都成功完成了日志复制的rpc请求(appendEntries)，则该写操作就是强一致下的写，因此可以将命令安全的提交到状态机中并向客户端返回成功。
* 线性强一致的读：强一致的读就不能让老leader处理读请求，因为很可能老leader相比实际上合法的新leader缺失了一些最新的写操作，而导致返回过时的数据(破坏了强一致读的语义)。因此对于读指令，业界提出了几种常见的确保强一致读的方案。
#####
|                       | 介绍                                                                                                                                                | 优点           | 缺点                           |
|-----------------------|---------------------------------------------------------------------------------------------------------------------------------------------------|--------------|------------------------------|
| 1.读写一视同仁              | 读操作也和写操作一样生成raftLog，并且向集群广播。如果广播得到了大多数节点的成功响应则作用到状态机中得到结果                                                                                         | 简单;易理解，易实现   | 相比其它方案，生成了太多不必要的读请求日志，性能极差   |
| 2.每次读操作前再确认一次leader地位 | 处理读操作前向集群发一个心跳广播，如果发现自己依然是leader则处理读请求                                                                                                            | 简单;容易实现      | 每次读请求都需要广播确认leader地位，性能也不是很好 |
| 3.周期性续期确认leader地位     | raft的论文中提到，leader选举的超时时间不能太小，否则心跳广播等无法抑制新选举。那么由此可得，当一次心跳广播确认leader地位后，在选举超时时间范围内自己一定还是集群中的合法leader。因此在一次周期心跳确认leader成功后，在选举超时时间范围内，所有的读请求都直接处理即可。 | 在高并发场合下性能会很好 | 实现起来稍显复杂，理解起来也有一定难度          |
  流行的raft实现都选择了性能最好的方案3，但MyRaft考虑到实现的简单性，选择了方案2来实现强一致的读。
* 
#####
#####
```java
    public ClientRequestResult clientRequest(ClientRequestParam clientRequestParam) {
        // 不是leader
        if(this.serverStatusEnum != ServerStatusEnum.LEADER){
            if(this.currentLeader == null){
                // 自己不是leader，也不知道谁是leader直接报错
                throw new MyRaftException("current node not leader，and leader is null! serverId=" + this.serverId);
            }

            RaftNodeConfig leaderConfig = this.raftConfig.getRaftNodeConfigList()
                .stream().filter(item-> Objects.equals(item.getServerId(), this.currentLeader)).findAny().get();

            // 把自己认为的leader告诉客户端(也可以改为直接转发请求)
            ClientRequestResult clientRequestResult = new ClientRequestResult();
            clientRequestResult.setLeaderAddress(new URLAddress(leaderConfig.getIp(),leaderConfig.getPort()));

            logger.info("not leader response known leader, result={}",clientRequestResult);
            return clientRequestResult;
        }

        // 是leader，处理读请求(线性一致读)
        if(clientRequestParam.getCommand() instanceof GetCommand){
            // 线性强一致的读，需要先进行一次心跳广播，判断当前自己是否还是leader
            boolean stillBeLeader = HeartbeatBroadcastTask.doHeartbeatBroadcast(this);
            if(stillBeLeader){
                // 还是leader，可以响应客户端
                logger.info("do client read op, still be leader");

                // Read-only operations can be handled without writing anything into the log.
                GetCommand getCommand = (GetCommand) clientRequestParam.getCommand();

                // 直接从状态机中读取就行
                String value = this.kvReplicationStateMachine.get(getCommand.getKey());

                ClientRequestResult clientRequestResult = new ClientRequestResult();
                clientRequestResult.setSuccess(true);
                clientRequestResult.setValue(value);

                logger.info("response getCommand, result={}",clientRequestResult);

                return clientRequestResult;
            }else{
                logger.info("do client read op, not still be leader");

                // 广播后发现自己不再是leader了，报错，让客户端重新自己找leader (客户端和当前节点同时误判，小概率发生)
                throw new MyRaftException("do client read op, but not still be leader!" + this.serverId);
            }
        }

        // 自己是leader，需要处理客户端的写请求

        // 构造新的日志条目
        LogEntry newLogEntry = new LogEntry();
        newLogEntry.setLogTerm(this.raftServerMetaDataPersistentModule.getCurrentTerm());
        // 新日志的索引号为当前最大索引编号+1
        newLogEntry.setLogIndex(this.logModule.getLastIndex() + 1);
        newLogEntry.setCommand(clientRequestParam.getCommand());

        logger.info("handle setCommand, do writeLocalLog entry={}",newLogEntry);

        // 预写入日志
        logModule.writeLocalLog(Collections.singletonList(newLogEntry));

        logger.info("handle setCommand, do writeLocalLog success!");

        List<AppendEntriesRpcResult> appendEntriesRpcResultList = logModule.replicationLogEntry(newLogEntry);

        logger.info("do replicationLogEntry, result={}",appendEntriesRpcResultList);

        // successNum需要加上自己的1票
        long successNum = appendEntriesRpcResultList.stream().filter(AppendEntriesRpcResult::isSuccess).count() + 1;
        if(successNum >= this.raftConfig.getMajorityNum()){
            // If command received from client: append entry to local log, respond after entry applied to state machine (§5.3)

            // 成功复制到多数节点

            // 设置最新的已提交索引编号
            logModule.setLastCommittedIndex(newLogEntry.getLogIndex());
            // 作用到状态机上
            this.kvReplicationStateMachine.apply((SetCommand) newLogEntry.getCommand());
            // 思考一下：lastApplied为什么不需要持久化？ 状态机指令的应用和更新lastApplied非原子性会产生什么问题？
            logModule.setLastApplied(newLogEntry.getLogIndex());

            // 返回成功
            ClientRequestResult clientRequestResult = new ClientRequestResult();
            clientRequestResult.setSuccess(true);

            return clientRequestResult;
        }else{
            // 没有成功复制到多数,返回失败
            ClientRequestResult clientRequestResult = new ClientRequestResult();
            clientRequestResult.setSuccess(false);

            // 删掉之前预写入的日志条目
            // 思考一下如果删除完成之前，宕机了有问题吗？ 个人感觉是ok的
            logModule.deleteLocalLog(newLogEntry.getLogIndex());

            return clientRequestResult;
        }
    }
```

##### MyRaft leader向集群广播raftLog

## 3. MyRaft日志复制功能验证

## 总结
