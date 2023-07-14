package myraft.module;

import myraft.RaftServer;
import myraft.api.model.RequestVoteRpcParam;
import myraft.api.model.RequestVoteRpcResult;
import myraft.common.enums.ServerStatusEnum;
import myraft.common.model.RaftServerMetaData;
import myraft.task.task.HeartBeatTimeoutCheckTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.concurrent.*;

/**
 * Raft服务器的leader选举模块
 * */
public class RaftLeaderElectionModule {

    private static final Logger logger = LoggerFactory.getLogger(RaftLeaderElectionModule.class);

    private final RaftServer currentServer;

    /**
     * 最近一次接受到心跳的时间
     * */
    private volatile Date lastHeartbeatTime;

    private final ScheduledExecutorService scheduledExecutorService;

    private final ExecutorService rpcThreadPool;

    public RaftLeaderElectionModule(RaftServer currentServer) {
        this.currentServer = currentServer;
        this.lastHeartbeatTime = new Date();
        this.scheduledExecutorService = Executors.newScheduledThreadPool(3);
        this.rpcThreadPool = Executors.newFixedThreadPool(
                Math.max(currentServer.getOtherNodeInCluster().size() * 2,1));

        registerHeartBeatTimeoutCheckTaskWithRandomTimeout();
    }

    /**
     * 提交新的延迟任务(带有随机化的超时时间)
     * */
    public void registerHeartBeatTimeoutCheckTaskWithRandomTimeout(){
//        logger.info("registerHeartBeatTimeoutCheckTaskWithRandomTimeout!");

        int electionTimeout = currentServer.getRaftConfig().getElectionTimeout();
        if(currentServer.getCurrentTerm() > 0 && currentServer.getRaftConfig().getDebugElectionTimeout() != null){
            // debug的时候多等待一些时间
            electionTimeout = currentServer.getRaftConfig().getDebugElectionTimeout();
        }

        long randomElectionTimeout = getRandomElectionTimeout();
        // 选举超时时间的基础上，加上一个随机化的时间
        long delayTime = randomElectionTimeout + electionTimeout * 1000L;
        logger.debug("registerHeartBeatTimeoutCheckTaskWithRandomTimeout delayTime={}",delayTime);
        scheduledExecutorService.schedule(
            new HeartBeatTimeoutCheckTask(currentServer,this),delayTime,TimeUnit.MILLISECONDS);
    }

    /**
     * 处理投票请求
     * 注意：synchronized修饰防止不同candidate并发的投票申请处理，以FIFO的方式处理
     * */
    public synchronized RequestVoteRpcResult requestVoteProcess(RequestVoteRpcParam requestVoteRpcParam){
        if(this.currentServer.getCurrentTerm() > requestVoteRpcParam.getTerm()){
            // Reply false if term < currentTerm (§5.1)
            // 发起投票的candidate任期小于当前服务器任期，拒绝投票给它
            logger.info("reject requestVoteProcess! term < currentTerm, currentServerId={}",currentServer.getServerId());
            return new RequestVoteRpcResult(this.currentServer.getCurrentTerm(),false);
        }

        // 发起投票的节点任期高于当前节点，无条件投票给它(任期高的说了算)
        if(this.currentServer.getCurrentTerm() < requestVoteRpcParam.getTerm()){
            // 刷新元数据
            this.currentServer.refreshRaftServerMetaData(
                new RaftServerMetaData(requestVoteRpcParam.getTerm(),requestVoteRpcParam.getCandidateId()));
            // 任期没它高，自己转为follower
            this.currentServer.setServerStatusEnum(ServerStatusEnum.FOLLOWER);
            return new RequestVoteRpcResult(this.currentServer.getCurrentTerm(),true);
        }

        // term任期值相同，需要避免同一任期内投票给不同的节点而脑裂
        if(this.currentServer.getVotedFor() != null && !this.currentServer.getVotedFor().equals(requestVoteRpcParam.getCandidateId())){
            // If votedFor is null or candidateId（取反的卫语句）
            // 当前服务器已经把票投给了别人,拒绝投票给发起投票的candidate
            logger.info("reject requestVoteProcess! votedFor={},currentServerId={}",
                currentServer.getVotedFor(),currentServer.getServerId());
            return new RequestVoteRpcResult(this.currentServer.getCurrentTerm(),false);
        }

        // 投票校验通过,刷新元数据
        this.currentServer.refreshRaftServerMetaData(
            new RaftServerMetaData(requestVoteRpcParam.getTerm(),requestVoteRpcParam.getCandidateId()));
        this.currentServer.processCommunicationHigherTerm(requestVoteRpcParam.getTerm());
        return new RequestVoteRpcResult(this.currentServer.getCurrentTerm(),true);
    }

    public void refreshLastHeartbeatTime(){
        // 刷新最新的接受到心跳的时间
        this.lastHeartbeatTime = new Date();
        // 接受新的心跳,说明现在leader是存活的，清理掉之前的投票信息
        this.currentServer.cleanVotedFor();

        logger.debug("refreshLastHeartbeatTime! {}",currentServer.getServerId());
    }

    public Date getLastHeartbeatTime() {
        return lastHeartbeatTime;
    }

    public ExecutorService getRpcThreadPool() {
        return rpcThreadPool;
    }

    private long getRandomElectionTimeout(){
        long min = currentServer.getRaftConfig().getElectionTimeoutRandomRange().getLeft();
        long max = currentServer.getRaftConfig().getElectionTimeoutRandomRange().getRight();

        // 生成[min,max]范围内随机整数的通用公式为：n=rand.nextInt(max-min+1)+min。
        return ThreadLocalRandom.current().nextLong(max-min+1) + min;
    }

}
