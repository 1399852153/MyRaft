package myraft;

import myraft.api.model.URLAddress;
import myraft.api.command.GetCommand;
import myraft.api.command.SetCommand;
import myraft.api.model.*;
import myraft.api.service.RaftService;
import myraft.common.config.RaftConfig;
import myraft.common.config.RaftNodeConfig;
import myraft.common.enums.ServerStatusEnum;
import myraft.common.model.RaftServerMetaData;
import myraft.common.model.RaftSnapshot;
import myraft.exception.MyRaftException;
import myraft.module.*;
import myraft.module.api.KVReplicationStateMachine;
import myraft.module.model.LocalLogEntry;
import myraft.task.HeartbeatBroadcastTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class RaftServer implements RaftService {

    private static final Logger logger = LoggerFactory.getLogger(RaftServer.class);

    /**
     * 当前服务节点的id(集群内全局唯一)
     * */
    private final String serverId;

    /**
     * Raft服务端配置
     * */
    private final RaftConfig raftConfig;

    /**
     * 当前服务器的状态
     * */
    private volatile ServerStatusEnum serverStatusEnum;

    /**
     * raft服务器元数据(当前任期值currentTerm、当前投票给了谁votedFor)
     * */
    private RaftServerMetaDataPersistentModule raftServerMetaDataPersistentModule;

    /**
     * 当前服务认为的leader节点的Id
     * */
    private volatile String currentLeader;

    /**
     * 集群中的其它raft节点服务
     * */
    protected List<RaftService> otherNodeInCluster;

    private RaftLeaderElectionModule raftLeaderElectionModule;
    private RaftHeartbeatBroadcastModule raftHeartbeatBroadcastModule;
    private LogModule logModule;
    private SnapshotModule snapshotModule;
    private KVReplicationStateMachine kvReplicationStateMachine;

    /**
     * nextIndex[]
     * */
    private final Map<RaftService,Long> nextIndexMap = new HashMap<>();

    /**
     * matchIndex[]
     * */
    private final Map<RaftService,Long> matchIndexMap = new HashMap<>();

    public RaftServer(RaftConfig raftConfig) {
        this.serverId = raftConfig.getServerId();
        this.raftConfig = raftConfig;
        // 初始化时都是follower
        this.serverStatusEnum = ServerStatusEnum.FOLLOWER;
    }

    public void init(List<RaftService> otherNodeInCluster){
        // 集群中的其它节点服务
        this.otherNodeInCluster = otherNodeInCluster;

        // 服务器元数据模块
        this.raftServerMetaDataPersistentModule = new RaftServerMetaDataPersistentModule(raftConfig.getServerId());
        try {
            // 日志模块
            logModule = new LogModule(this);
        } catch (IOException e) {
            throw new MyRaftException("init LogModule error!",e);
        }

        // leader选举模块
        raftLeaderElectionModule = new RaftLeaderElectionModule(this);
        // leader心跳广播模块
        raftHeartbeatBroadcastModule = new RaftHeartbeatBroadcastModule(this);
        // 状态机模块
        kvReplicationStateMachine = new SimpleReplicationStateMachine(this);

        logger.info("raft server init success! otherNodeInCluster={}, currentServerId={}",otherNodeInCluster,serverId);
    }

    @Override
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

    @Override
    public RequestVoteRpcResult requestVote(RequestVoteRpcParam requestVoteRpcParam) {
        RequestVoteRpcResult requestVoteRpcResult = raftLeaderElectionModule.requestVoteProcess(requestVoteRpcParam);

        processCommunicationHigherTerm(requestVoteRpcParam.getTerm());

        logger.info("do requestVote requestVoteRpcParam={},requestVoteRpcResult={}, currentServerId={}",
            requestVoteRpcParam,requestVoteRpcResult,this.serverId);

        return requestVoteRpcResult;
    }

    @Override
    public AppendEntriesRpcResult appendEntries(AppendEntriesRpcParam appendEntriesRpcParam) {
        if(appendEntriesRpcParam.getTerm() < this.raftServerMetaDataPersistentModule.getCurrentTerm()){
            // Reply false if term < currentTerm (§5.1)
            // 拒绝处理任期低于自己的老leader的请求

            logger.info("doAppendEntries term < currentTerm");
            return new AppendEntriesRpcResult(this.raftServerMetaDataPersistentModule.getCurrentTerm(),false);
        }

        if(appendEntriesRpcParam.getTerm() >= this.raftServerMetaDataPersistentModule.getCurrentTerm()){
            // appendEntries请求中任期值如果大于自己，说明已经有一个更新的leader了，自己转为follower，并且以对方更大的任期为准
            this.serverStatusEnum = ServerStatusEnum.FOLLOWER;
            this.currentLeader = appendEntriesRpcParam.getLeaderId();
            this.raftServerMetaDataPersistentModule.setCurrentTerm(appendEntriesRpcParam.getTerm());
        }

        if(appendEntriesRpcParam.getEntries() == null || appendEntriesRpcParam.getEntries().isEmpty()){
            // 来自leader的心跳处理，清理掉之前选举的votedFor
            this.cleanVotedFor();
            // entries为空，说明是心跳请求，刷新一下最近收到心跳的时间
            raftLeaderElectionModule.refreshLastHeartbeatTime();

            long currentLastCommittedIndex = logModule.getLastCommittedIndex();
            logger.debug("doAppendEntries heartbeat leaderCommit={},currentLastCommittedIndex={}",
                appendEntriesRpcParam.getLeaderCommit(),currentLastCommittedIndex);

            if(appendEntriesRpcParam.getLeaderCommit() > currentLastCommittedIndex) {
                // 心跳处理里，如果leader当前已提交的日志进度超过了当前节点的进度，令当前节点状态机也跟上
                // 如果leaderCommit >= logModule.getLastIndex(),说明当前节点的日志进度不足，但可以把目前已有的日志都提交给状态机去执行
                // 如果leaderCommit < logModule.getLastIndex(),说明当前节点进度比较快，有一些日志是leader已复制但还没提交的，把leader已提交的那一部分作用到状态机就行
                long minNeedCommittedIndex = Math.min(appendEntriesRpcParam.getLeaderCommit(), logModule.getLastIndex());
                pushStatemachineApply(minNeedCommittedIndex);
            }

            // 心跳请求，直接返回
            return new AppendEntriesRpcResult(this.raftServerMetaDataPersistentModule.getCurrentTerm(),true);
        }

        // logEntries不为空，是真实的日志复制rpc

        logger.info("do real log append! appendEntriesRpcParam={}",appendEntriesRpcParam);
        // AppendEntry可靠性校验，如果prevLogIndex和prevLogTerm不匹配，则需要返回false，让leader发更早的日志过来
        {
            LogEntry localPrevLogEntry = logModule.readLocalLog(appendEntriesRpcParam.getPrevLogIndex());
            if(localPrevLogEntry == null){
                // 当前节点日志条目为空，说明完全没有日志(默认任期为-1，这个是约定)
                localPrevLogEntry = LogEntry.getEmptyLogEntry();
            }

            if (localPrevLogEntry.getLogTerm() != appendEntriesRpcParam.getPrevLogTerm()) {
                //  Reply false if log doesn’t contain an entry at prevLogIndex
                //  whose term matches prevLogTerm (§5.3)
                //  本地日志和参数中的PrevLogIndex和PrevLogTerm对不上(对应日志不存在，或者任期对不上)
                logger.info("doAppendEntries localPrevLogEntry not match, localLogEntry={}",localPrevLogEntry);

                return new AppendEntriesRpcResult(this.raftServerMetaDataPersistentModule.getCurrentTerm(),false);
            }
        }

        // 走到这里说明找到了最新的一条匹配的记录
        logger.info("doAppendEntries localEntry is match");

        List<LogEntry> newLogEntryList = appendEntriesRpcParam.getEntries();

        // 1. Append any new entries not already in the log
        // 2. If an existing entry conflicts with a new one (same index but different terms),
        //    delete the existing entry and all that follow it (§5.3)
        // 新日志的复制操作（直接整个覆盖掉prevLogIndex之后的所有日志,以leader发过来的日志为准）
        logModule.writeLocalLog(newLogEntryList, appendEntriesRpcParam.getPrevLogIndex());

        // If leaderCommit > commitIndex, set commitIndex = min(leaderCommit, index of last new entry)
        if(appendEntriesRpcParam.getLeaderCommit() > logModule.getLastCommittedIndex()){
            // 如果leaderCommit更大，说明当前节点的同步进度慢于leader，以新的entry里的index为准(更高的index还没有在本地保存(因为上面的appendEntry有效性检查))
            // 如果index of last new entry更大，说明当前节点的同步进度是和leader相匹配的，commitIndex以leader最新提交的为准

            LogEntry lastNewEntry = newLogEntryList.get(newLogEntryList.size()-1);
            long lastCommittedIndex = Math.min(appendEntriesRpcParam.getLeaderCommit(), lastNewEntry.getLogIndex());
            pushStatemachineApply(lastCommittedIndex);
        }

        // 返回成功
        return new AppendEntriesRpcResult(this.raftServerMetaDataPersistentModule.getCurrentTerm(), true);
    }

    @Override
    public InstallSnapshotRpcResult installSnapshot(InstallSnapshotRpcParam installSnapshotRpcParam) {
        logger.info("installSnapshot start! serverId={},installSnapshotRpcParam={}",this.serverId,installSnapshotRpcParam);

        if(installSnapshotRpcParam.getTerm() < this.raftServerMetaDataPersistentModule.getCurrentTerm()){
            // Reply immediately if term < currentTerm
            // 拒绝处理任期低于自己的老leader的请求

            logger.info("installSnapshot term < currentTerm");
            return new InstallSnapshotRpcResult(this.raftServerMetaDataPersistentModule.getCurrentTerm());
        }

        // 安装快照
        this.snapshotModule.appendInstallSnapshot(installSnapshotRpcParam);

        // 快照已经完全安装好了
        if(installSnapshotRpcParam.isDone()){
            // discard any existing or partial snapshot with a smaller index
            // 快照整体安装完毕，清理掉index小于等于快照中lastIncludedIndex的所有日志(日志压缩)
            logModule.compressLogBySnapshot(installSnapshotRpcParam);

            // Reset state machine using snapshot contents (and load snapshot’s cluster configuration)
            // follower的状态机重新安装快照
            RaftSnapshot raftSnapshot = this.snapshotModule.readSnapshot();
            kvReplicationStateMachine.installSnapshot(raftSnapshot.getSnapshotData());
        }

        logger.info("installSnapshot end! serverId={}",this.serverId);

        return new InstallSnapshotRpcResult(this.raftServerMetaDataPersistentModule.getCurrentTerm());
    }

    private void pushStatemachineApply(long lastCommittedIndex){
        long lastApplied = logModule.getLastApplied();

        // If commitIndex > lastApplied: increment lastApplied, apply log[lastApplied] to state machine (§5.3)
        if(lastApplied < lastCommittedIndex){
            // 作用在状态机上的日志编号低于集群中已提交的日志编号，需要把这些已提交的日志都作用到状态机上去
            logger.info("pushStatemachineApply.apply, lastApplied={},lastCommittedIndex={}",lastApplied,lastCommittedIndex);

            // 全读取出来(读取出来是按照index从小到大排好序的)
            List<LocalLogEntry> logEntryList = logModule.readLocalLog(lastApplied+1,lastCommittedIndex);

            logger.info("pushStatemachineApply.apply, logEntryList={}",logEntryList);

            List<SetCommand> setCommandList = logEntryList.stream()
                .filter(item->item.getCommand() instanceof SetCommand)
                .map(item->(SetCommand)item.getCommand())
                .collect(Collectors.toList());

            // 按照顺序依次作用到状态机中
            this.kvReplicationStateMachine.batchApply(setCommandList);
        }

        this.logModule.setLastCommittedIndex(lastCommittedIndex);
        this.logModule.setLastApplied(lastCommittedIndex);
    }

    // ================================= get/set ============================================

    public String getServerId() {
        return serverId;
    }

    public RaftConfig getRaftConfig() {
        return raftConfig;
    }

    public void setServerStatusEnum(ServerStatusEnum serverStatusEnum) {
        this.serverStatusEnum = serverStatusEnum;
    }

    public ServerStatusEnum getServerStatusEnum() {
        return serverStatusEnum;
    }

    public int getCurrentTerm() {
        return this.raftServerMetaDataPersistentModule.getCurrentTerm();
    }

    public String getVotedFor() {
        return this.raftServerMetaDataPersistentModule.getVotedFor();
    }

    public void setCurrentTerm(int currentTerm) {
        this.raftServerMetaDataPersistentModule.setCurrentTerm(currentTerm);
    }

    public void setVotedFor(String votedFor) {
        this.raftServerMetaDataPersistentModule.setVotedFor(votedFor);
    }

    public String getCurrentLeader() {
        return currentLeader;
    }

    public void setCurrentLeader(String currentLeader) {
        this.currentLeader = currentLeader;
    }

    public List<RaftService> getOtherNodeInCluster() {
        return otherNodeInCluster;
    }

    public void setOtherNodeInCluster(List<RaftService> otherNodeInCluster) {
        this.otherNodeInCluster = otherNodeInCluster;
    }

    public RaftLeaderElectionModule getRaftLeaderElectionModule() {
        return raftLeaderElectionModule;
    }

    public RaftHeartbeatBroadcastModule getRaftHeartbeatBroadcastModule() {
        return raftHeartbeatBroadcastModule;
    }

    public LogModule getLogModule() {
        return logModule;
    }

    public SnapshotModule getSnapshotModule() {
        return snapshotModule;
    }

    public KVReplicationStateMachine getKvReplicationStateMachine() {
        return kvReplicationStateMachine;
    }

    public void refreshRaftServerMetaData(RaftServerMetaData raftServerMetaData){
        this.raftServerMetaDataPersistentModule.refreshRaftServerMetaData(raftServerMetaData);
    }

    public Map<RaftService, Long> getNextIndexMap() {
        return nextIndexMap;
    }

    public Map<RaftService, Long> getMatchIndexMap() {
        return matchIndexMap;
    }

    // ============================= public的业务接口 =================================

    /**
     * 清空votedFor
     * */
    public void cleanVotedFor(){
        this.raftServerMetaDataPersistentModule.setVotedFor(null);
    }

    /**
     * rpc交互时任期高于当前节点任期的处理
     * (同时包括接到的rpc请求以及得到的rpc响应，只要另一方的term高于当前节点的term，就更新当前节点的term值)
     *
     * Current terms are exchanged whenever servers communicate;
     * if one server’s current term is smaller than the other’s, then it updates its current term to the larger value.
     *
     * @return 是否转换为了follower
     * */
    public boolean processCommunicationHigherTerm(int rpcOtherTerm){
        // If RPC request or response contains term T > currentTerm:
        // set currentTerm = T, convert to follower (§5.1)
        if(rpcOtherTerm > this.getCurrentTerm()) {
            this.setCurrentTerm(rpcOtherTerm);
            this.setServerStatusEnum(ServerStatusEnum.FOLLOWER);

            return true;
        }else{
            return false;
        }
    }
}
