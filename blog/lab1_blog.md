## 自己动手实现基于Raft的简易KV数据库(一) 实现leader选举
## 1. 一致性算法介绍
### 1.1 一致性同步与Paxos算法
* 对可靠性有很高要求的系统，通常都会额外部署1至多个机器为备用副本组成主备集群，避免出现单点故障。
  有状态的系统需要主节点与备用副本间以某种方式进行数据复制，这样主节点出现故障时就能快速的令备用机器接管系统以达到高可用的目的。
* 常见的主备复制方式是异步、弱一致性的，例如DNS系统，mysql、redis(7.0之前)等数据库的主备复制，或者通过某种消息中间件来进行解耦，即在CAP中选择了AP。  
  弱一致性的AP相比强一致CP的复制有着许多优点：**效率高**(多个单次操作可以批量处理)，**耦合性低**(备份节点挂了也不影响主节点工作)，实现相对简单。  
  但AP复制最大的缺点就是**丧失了强一致性**，主节点在操作完成响应客户端后，但还未成功同步到备份节点前宕机，对应变更将会丢失，因此AP的方案不适用于对一致性有苛刻要求的场合。
* 原始的强一致性主备同步，即主节点在每一个备份节点同步完成后才能响应客户端成功的方案效率太低，可用性太差(任意一个备份节点故障就会使得集群不可用)。  
  因此基于多数派的分布式强一致算法被发明了出来，其中最有名的便是**Paxos**算法。但Paxos算法过于复杂，在分布式环境下有大量的case需要得到正确的实现，因此时至今日也没有多少系统真正的将Paxos落地。  
### 1.2 raft算法
* 由于Paxos过于复杂的原因，Raft算法被发明了出来。Raft算法在设计时大量参考了Paxos，也是一个基于日志和多数派的一致性算法，但在很多细节上相比Paxos做了许多简化。 
* 因为Raft比Paxos要简单很多，更容易被开发人员理解并最终用于构建实际的系统，因此目前很多流行的强一致分布式系统都是基于Raft算法的。  
#####
[raft的论文](https://www.cnblogs.com/xiaoxiongcanguan/p/17552027.html) 中将raft算法的功能分解为4个模块：
1. leader选举
2. 日志复制
3. 日志压缩
4. 集群成员动态变更
#####
其中前两项“leader选举”和“日志复制”是raft算法的基础，而后两项“日志压缩”和“集群成员动态变更”属于raft算法在功能上重要的优化。

## 2. 自己动手实现一个基于Raft的简易KV数据库
通过raft的论文或者其它相关资料，读者可以大致的理解raft的工作原理。  
但纸上得来终觉浅，绝知此事要躬行，亲手实践才能更好的把握raft中的精巧细节，加深对raft算法的理解，更有效率的阅读基于raft的开源项目代码。
##### MyRaft
在这个系列博客中会带领读者一步步实现一个基于raft算法的简易KV数据库,即MyRaft。  
MyRaft实现了raft论文中提到的三个功能，即leader选举、日志复制和日志压缩（在实践中发现“集群成员动态变更”会对原有逻辑进行较大改动而大幅增加复杂度，限于个人水平暂不实现）。  
三个功能会通过三次迭代逐步完成，每个迭代都会以博客的形式进行解析。  

## 3. MyRaft基础结构源码分析
* 由于是MyRaft的第一个迭代，在这个迭代中需要先搭好MyRaft的基础架子。  
  raft中的每个节点本质上是一个rpc服务器，同时每个节点都能通过rpc的方式与其它节点进行通信。  
* 其中使用的rpc框架是上一个实验中自己实现的MyRpc框架：  
  博客地址: https://www.cnblogs.com/xiaoxiongcanguan/p/17506728.html  
  github地址：https://github.com/1399852153/MyRpc (main分支)  
#####
todo 附MyRaft基础架构图
##### Raft的rpc接口定义
* **因为lab1中只实现leader选举，简单起见只定义当前所需的api接口，接口参数相比最终的实现也省去了大量当前用不上的字段，后续有需要再进行拓展。**
```java
public interface RaftService {

    /**
     * 请求投票 requestVote
     *
     * Receiver implementation:
     * 1. Reply false if term < currentTerm (§5.1)
     * 2. If votedFor is null or candidateId, and candidate’s log is at
     * least as up-to-date as receiver’s log, grant vote (§5.2, §5.4)
     *
     * 接受者需要实现以下功能：
     * 1. 如果参数中的任期值term小于当前自己的任期值currentTerm，则返回false不同意投票给调用者
     * 2. 如果自己还没有投票(FIFO)或者已经投票给了candidateId对应的节点(幂等)，
     *    并且候选人的日志至少与被调用者的日志一样新(比较日志的任期值和索引值)，则投票给调用者(返回值里voteGranted为true)
     * */
    RequestVoteRpcResult requestVote(RequestVoteRpcParam requestVoteRpcParam);

    /**
     * 追加日志条目 AppendEntries
     * */
    AppendEntriesRpcResult appendEntries(AppendEntriesRpcParam appendEntriesRpcParam);
}
```
```java
/**
 * 请求投票的RPC接口参数对象
 */
public class RequestVoteRpcParam implements Serializable {

    /**
     * 候选人的任期编号
     * */
    private int term;

    /**
     * 候选人的Id
     * */
    private String candidateId;

    /**
     * 候选人最新日志的索引编号
     * */
    private long lastLogIndex;

    /**
     * 候选人最新日志对应的任期编号
     * */
    private int lastLogTerm;
}
```
```java
/**
 * 请求投票的RPC接口响应对象
 * */
public class RequestVoteRpcResult implements Serializable {

    /**
     * 被调用者当前的任期值
     * */
    private int term;

    /**
     * 是否同意投票给调用者
     * */
    private boolean voteGranted;
}
```
```java
/**
 * 追加日志条目的RPC接口参数对象
 * */
public class AppendEntriesRpcParam implements Serializable {

    /**
     * 当前leader的任期值
     * */
    private int term;

    /**
     * leader的id
     * */
    private String leaderId;
}
```
```java
/**
 * 追加日志条目的RPC接口响应对象
 * */
public class AppendEntriesRpcResult implements Serializable {

    /**
     * 被调用者当前的任期值
     * */
    private int term;

    /**
     * 是否处理成功
     * */
    private boolean success;
}
```
##### MyRaft的Rpc客户端与服务端实现
```java
/**
 * raft的rpc服务
 * */
public class RaftRpcServer extends RaftServer {

    private final Registry registry;
    private final RaftNodeConfig currentNodeConfig;

    public RaftRpcServer(RaftConfig raftConfig, Registry registry){
        super(raftConfig);

        this.currentNodeConfig = raftConfig.getCurrentNodeConfig();
        this.registry = registry;
    }

    @Override
    public void init(List<RaftService> otherNodeInCluster) {
        // 先初始化内部模块
        super.init(otherNodeInCluster);

        // 初始化内部的模块后，启动rpc
        initRpcServer();
    }

    public List<RaftService> getRpcProxyList(List<RaftNodeConfig> otherNodeInCluster){
        return initRpcConsumer(otherNodeInCluster);
    }

    private List<RaftService> initRpcConsumer(List<RaftNodeConfig> otherNodeInCluster){
        ConsumerBootstrap consumerBootstrap = new ConsumerBootstrap()
            .registry(registry)
            .loadBalance(new SimpleRoundRobinBalance());

        // 注册消费者
        Consumer<RaftService> consumer = consumerBootstrap.registerConsumer(RaftService.class,new FastFailInvoker());
        RaftService raftServiceProxy = consumer.getProxy();

        List<RaftService> raftRpcConsumerList = new ArrayList<>();
        for(RaftNodeConfig raftNodeConfig : otherNodeInCluster){
            // 使用rpc代理的客户端
            raftRpcConsumerList.add(new RaftRpcConsumer(raftNodeConfig,raftServiceProxy));
        }

        return raftRpcConsumerList;
    }

    private void initRpcServer(){
        URLAddress providerURLAddress = new URLAddress(currentNodeConfig.getIp(),currentNodeConfig.getPort());
        Provider<RaftService> provider = new Provider<>();
        provider.setInterfaceClass(RaftService.class);
        provider.setRef(this);
        provider.setUrlAddress(providerURLAddress);
        provider.setRegistry(registry);
        provider.export();

        NettyServer nettyServer = new NettyServer(providerURLAddress);
        nettyServer.init();
    }
}
```
```java
public class RaftRpcConsumer implements RaftService {

    private static final Logger logger = LoggerFactory.getLogger(RaftRpcConsumer.class);

    private final RaftNodeConfig targetNodeConfig;
    private final RaftService raftServiceProxy;

    public RaftRpcConsumer(RaftNodeConfig targetNodeConfig, RaftService proxyRaftService) {
        this.targetNodeConfig = targetNodeConfig;
        this.raftServiceProxy = proxyRaftService;
    }

    @Override
    public RequestVoteRpcResult requestVote(RequestVoteRpcParam requestVoteRpcParam) {
        // 强制指定rpc目标的ip/port
        setTargetProviderUrl();
        RequestVoteRpcResult result = raftServiceProxy.requestVote(requestVoteRpcParam);
        return result;
    }

    @Override
    public AppendEntriesRpcResult appendEntries(AppendEntriesRpcParam appendEntriesRpcParam) {
        // 强制指定rpc目标的ip/port
        setTargetProviderUrl();
        AppendEntriesRpcResult result = raftServiceProxy.appendEntries(appendEntriesRpcParam);
        return result;
    }

    private void setTargetProviderUrl(){
        ConsumerRpcContext consumerRpcContext = ConsumerRpcContextHolder.getConsumerRpcContext();
        consumerRpcContext.setTargetProviderAddress(
            new URLAddress(targetNodeConfig.getIp(),targetNodeConfig.getPort()));
    }
}
```
##### raft服务元数据持久化
* raft的论文中提到raft服务中需要持久化的三个要素：currentTerm（当前服务器的任期值）、votedFor(当前服务器在此之前投票给了谁)和logs(raft的操作日志，与本篇博客无关在lab2中才会引入)。  
* currentTerm和votedFor需要持久化的原因是为了避免raft节点在完成leader选举的投票后宕机，重启恢复后如果这两个数据丢失了就很容易在同一任期内投票给多个候选人而出现集群脑裂(即多个合法leader)。
* MyRaft用磁盘文件进行持久化，简单起见在currentTerm或votedFor更新时加写锁，通过原子性的整体刷盘来完成持久化。
#####
```java
public class RaftServerMetaData {

    /**
     * 当前服务器的任期值
     * */
    private int currentTerm;

    /**
     * 当前服务器在此之前投票给了谁？
     * (候选者的serverId，如果还没有投递就是null)
     * */
    private String votedFor;
}
```
```java
public class RaftServerMetaDataPersistentModule {

    /**
     * 当前服务器的任期值
     * */
    private volatile int currentTerm;

    /**
     * 当前服务器在此之前投票给了谁？
     * (候选者的serverId，如果还没有投递就是null)
     * */
    private volatile String votedFor;

    private final File persistenceFile;

    private final ReentrantReadWriteLock reentrantLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = reentrantLock.writeLock();
    private final ReentrantReadWriteLock.ReadLock readLock = reentrantLock.readLock();

    public RaftServerMetaDataPersistentModule(String serverId) {
        String userPath = System.getProperty("user.dir") + File.separator + serverId;

        this.persistenceFile = new File(userPath + File.separator + "raftServerMetaData-" + serverId + ".txt");
        MyRaftFileUtil.createFile(persistenceFile);

        // 读取持久化在磁盘中的数据
        RaftServerMetaData raftServerMetaData = readRaftServerMetaData(persistenceFile);
        this.currentTerm = raftServerMetaData.getCurrentTerm();
        this.votedFor = raftServerMetaData.getVotedFor();
    }

    public int getCurrentTerm() {
        readLock.lock();
        try {
            return currentTerm;
        }finally {
            readLock.unlock();
        }
    }

    public void setCurrentTerm(int currentTerm) {
        writeLock.lock();
        try {
            this.currentTerm = currentTerm;

            // 更新后数据落盘
            persistentRaftServerMetaData(new RaftServerMetaData(this.currentTerm,this.votedFor),persistenceFile);
        }finally {
            writeLock.unlock();
        }
    }

    public String getVotedFor() {
        readLock.lock();
        try {
            return votedFor;
        }finally {
            readLock.unlock();
        }
    }

    public void setVotedFor(String votedFor) {
        writeLock.lock();
        try {
            this.votedFor = votedFor;

            // 更新后数据落盘
            persistentRaftServerMetaData(new RaftServerMetaData(this.currentTerm,this.votedFor),persistenceFile);
        }finally {
            writeLock.unlock();
        }
    }

    private static RaftServerMetaData readRaftServerMetaData(File persistenceFile){
        String content = MyRaftFileUtil.getFileContent(persistenceFile);
        if(StringUtils.hasText(content)){
            return JsonUtil.json2Obj(content,RaftServerMetaData.class);
        }else{
            return RaftServerMetaData.getDefault();
        }
    }

    private static void persistentRaftServerMetaData(RaftServerMetaData raftServerMetaData, File persistenceFile){
        String content = JsonUtil.obj2Str(raftServerMetaData);

        MyRaftFileUtil.writeInFile(persistenceFile,content);
    }
}
```
## 3. leader选举原理与MyRaft实现解析
### leader选举原理
raft的leader选举在论文中有比较详细的描述，这里说一下我认为的关键细节。
* Raft算法中leader扮演着绝对核心的角色，leader负责处理客户端的请求、将操作日志同步给其它的follower节点以及通知follower提交日志等等。  
  因此Raft集群必须基于多数原则选举出一个存活的leader才能对外提供服务，并且一个任期内只能有一个基于多数票选出的leader。
* 在raft节点刚启动时是处于默认的follower追随者状态的。如果在一段时间内raft节点没有接受到来自leader的定时心跳rpc(logEntry为空的appendEntries)通知时就会发起一轮新的选举。
  产生这个现象的原因有很多，比如集群刚刚启动还没有leader；或者之前的leader因为某种原因宕机或与follower的网络通信出现故障等。
* 发起请求的follower会转变为candidate候选人状态，并首先投票给自己。  
  同时并行的向集群中的其它节点发起请求投票的rpc请求(requestVote),可以理解为给自己拉票。接收到requestVote请求的节点会根据自身的状态等信息决定是否投票给发起投票的节点(具体的限制可以看源码中)。  
  当candidate获得了集群中超过半数的投票(即包括自己在内的1票加上requestVote返回投票成功的数量超过半数)，则candidate成为当前任期的leader。  
  如果没有任何一个candidate获得多数选票(没选出leader，可能是分票了，也可能是网络波动等等)，则candidate会将当前任期自增1，则在下一次选举超时时会再触发一轮新的选举，循环往复直至选出leader。
* leader当选后需要立即向其它的节点发送心跳rpc(logEntry为空的appendEntries)，昭告新leader的产生以抑制其它节点发起新的选举。  
  心跳rpc必须以一定频率的定时向所有follower发送，发送的时间间隔需要小于设置的选举超时时间。
* 由于处理requestVote是先到先得的，同一任期内先发起投票请求的candidate会收到票，后发送的会被拒绝。  
  假如leader宕机了，则每个follower都会在一段时间后触发新一轮选举，如果没有额外的限制，则每个节点并发的发起选举很容易导致分票，即自己投给自己，则难以达成共识(取得多数票)。  
  Raft的论文中提出了随机化选举超时时间的方案，即每个follower节点的选举超时时间是一个固定值再加上一个随机化的值得到的，这样很难在同一瞬间都触发选举。  
  随机超时时间更短的follower能够最先发起选举，更快的得到其它节点的投票从而避免分票的情况。  
  虽然无法完全避免分票，但实践中发现效果很好，随机超时时间下通常少数的几次分票后就能收敛而选出leader来。
* 注意：在本篇博客中requestVote较为简单，但实际上raft在5.4安全性中提到的，对于candidate的日志有一定的要求(只有拥有完整日志的节点才有资格成为leader)，
  但lab1中不支持日志复制，所以在lab1中的MyRaft在requestVote实现中省略了相关逻辑。

todo raft节点状态变化图
## 总结