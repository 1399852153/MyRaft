package myraft;

import myraft.api.model.AppendEntriesRpcParam;
import myraft.api.model.AppendEntriesRpcResult;
import myraft.api.model.RequestVoteRpcParam;
import myraft.api.model.RequestVoteRpcResult;
import myraft.api.service.RaftService;
import myraft.common.config.RaftConfig;
import myraft.common.enums.ServerStatusEnum;
import myraft.common.model.RaftServerMetaData;
import myraft.module.RaftHeartBeatBroadcastModule;
import myraft.module.RaftLeaderElectionModule;
import myraft.module.RaftServerMetaDataPersistentModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private final RaftServerMetaDataPersistentModule raftServerMetaDataPersistentModule;

    /**
     * 当前服务认为的leader节点的Id
     * */
    private volatile String currentLeader;

    /**
     * 集群中的其它raft节点服务
     * */
    protected List<RaftService> otherNodeInCluster;

    private RaftLeaderElectionModule raftLeaderElectionModule;
    private RaftHeartBeatBroadcastModule raftHeartBeatBroadcastModule;

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

        // 服务器元数据模块
        this.raftServerMetaDataPersistentModule = new RaftServerMetaDataPersistentModule(raftConfig.getServerId());
    }

    public void init(List<RaftService> otherNodeInCluster){
        // 集群中的其它节点服务
        this.otherNodeInCluster = otherNodeInCluster;

        raftLeaderElectionModule = new RaftLeaderElectionModule(this);
        raftHeartBeatBroadcastModule = new RaftHeartBeatBroadcastModule(this);

        logger.info("raft server init end! otherNodeInCluster={}, currentServerId={}",otherNodeInCluster,serverId);
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

        // 来自leader的心跳处理，清理掉之前选举的votedFor
        this.cleanVotedFor();
        // entries为空，说明是心跳请求，刷新一下最近收到心跳的时间
        raftLeaderElectionModule.refreshLastHeartbeatTime();

        // 心跳请求，直接返回
        return new AppendEntriesRpcResult(this.raftServerMetaDataPersistentModule.getCurrentTerm(),true);
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

    public RaftHeartBeatBroadcastModule getRaftHeartBeatBroadcastModule() {
        return raftHeartBeatBroadcastModule;
    }

    public void refreshRaftServerMetaData(RaftServerMetaData raftServerMetaData){
        this.raftServerMetaDataPersistentModule.refreshRaftServerMetaData(raftServerMetaData);
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
