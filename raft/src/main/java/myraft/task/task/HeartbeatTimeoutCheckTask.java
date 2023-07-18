package myraft.task.task;

import myraft.RaftServer;
import myraft.api.model.RequestVoteRpcParam;
import myraft.api.model.RequestVoteRpcResult;
import myraft.api.service.RaftService;
import myraft.common.enums.ServerStatusEnum;
import myraft.module.RaftLeaderElectionModule;
import myraft.util.util.CommonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 心跳超时检查任务
 * */
public class HeartbeatTimeoutCheckTask implements Runnable{

    private static final Logger logger = LoggerFactory.getLogger(HeartbeatTimeoutCheckTask.class);

    private final RaftServer currentServer;
    private final RaftLeaderElectionModule raftLeaderElectionModule;

    public HeartbeatTimeoutCheckTask(RaftServer currentServer, RaftLeaderElectionModule raftLeaderElectionModule) {
        this.currentServer = currentServer;
        this.raftLeaderElectionModule = raftLeaderElectionModule;
    }

    @Override
    public void run() {
        if(currentServer.getServerStatusEnum() == ServerStatusEnum.LEADER){
            // leader是不需要处理心跳超时的
            // 注册下一个心跳检查任务
            raftLeaderElectionModule.registerHeartbeatTimeoutCheckTaskWithRandomTimeout();
        }else{
            try {
                doTask();
            }catch (Exception e){
                logger.info("do HeartbeatTimeoutCheckTask error! ignore",e);
            }

            // 注册下一个心跳检查任务
            raftLeaderElectionModule.registerHeartbeatTimeoutCheckTaskWithRandomTimeout();
        }
    }

    private void doTask(){
        logger.debug("do HeartbeatTimeoutCheck start {}",currentServer.getServerId());

        int electionTimeout = currentServer.getRaftConfig().getElectionTimeout();

        // 当前时间
        Date currentDate = new Date();
        Date lastHeartbeatTime = raftLeaderElectionModule.getLastHeartbeatTime();
        long diffTime = currentDate.getTime() - lastHeartbeatTime.getTime();

        logger.debug("currentDate={}, lastHeartbeatTime={}, diffTime={}, serverId={}",
            currentDate,lastHeartbeatTime,diffTime,currentServer.getServerId());
        // 心跳超时判断
        if(diffTime > (electionTimeout * 1000L)){
            logger.info("HeartbeatTimeoutCheck check fail, trigger new election! serverId={}",currentServer.getServerId());

            // 触发新的一轮选举
            triggerNewElection();
        }else{
            // 认定为心跳正常，无事发生
            logger.debug("HeartbeatTimeoutCheck check success {}",currentServer.getServerId());
        }

        logger.debug("do HeartbeatTimeoutCheck end {}",currentServer.getServerId());
    }

    private void triggerNewElection(){
        logger.info("HeartbeatTimeoutCheck check fail, trigger new election! serverId={}",currentServer.getServerId());

        // 距离最近一次接到心跳已经超过了选举超时时间，触发新一轮选举

        // 当前服务器节点当前任期自增1
        currentServer.setCurrentTerm(currentServer.getCurrentTerm()+1);
        // 自己发起选举，先投票给自己
        currentServer.setVotedFor(currentServer.getServerId());
        // 角色转变为CANDIDATE候选者
        currentServer.setServerStatusEnum(ServerStatusEnum.CANDIDATE);

        // 并行的发送请求投票的rpc给集群中的其它节点
        List<RaftService> otherNodeInCluster = currentServer.getOtherNodeInCluster();
        List<Future<RequestVoteRpcResult>> futureList = new ArrayList<>(otherNodeInCluster.size());

        // 构造请求参数
        RequestVoteRpcParam requestVoteRpcParam = new RequestVoteRpcParam();
        requestVoteRpcParam.setTerm(currentServer.getCurrentTerm());
        requestVoteRpcParam.setCandidateId(currentServer.getServerId());

        for(RaftService node : otherNodeInCluster){
            Future<RequestVoteRpcResult> future = raftLeaderElectionModule.getRpcThreadPool().submit(
                ()-> {
                    RequestVoteRpcResult rpcResult = node.requestVote(requestVoteRpcParam);
                    // 收到更高任期的处理
                    currentServer.processCommunicationHigherTerm(rpcResult.getTerm());
                    return rpcResult;
                }
            );

            futureList.add(future);
        }

        List<RequestVoteRpcResult> requestVoteRpcResultList = CommonUtil.concurrentGetRpcFutureResult(
            "requestVote", futureList,
            raftLeaderElectionModule.getRpcThreadPool(),1,TimeUnit.SECONDS);

        // 获得rpc响应中决定投票给自己的总票数（算上自己的1票）
        int getRpcVoted = (int) requestVoteRpcResultList.stream().filter(RequestVoteRpcResult::isVoteGranted).count()+1;
        logger.info("HeartbeatTimeoutCheck election, getRpcVoted={}, currentServerId={}",getRpcVoted,currentServer.getServerId());

        // 是否获得大多数的投票
        boolean majorVoted = getRpcVoted >= this.currentServer.getRaftConfig().getMajorityNum();
        if(majorVoted){
            logger.info("HeartbeatTimeoutCheck election result: become a leader! {}, currentTerm={}",currentServer.getServerId(),currentServer.getCurrentTerm());

            // 票数过半成功当选为leader
            currentServer.setServerStatusEnum(ServerStatusEnum.LEADER);
            currentServer.setCurrentLeader(currentServer.getServerId());

            // 成为leader后立马发送一次心跳,抑制其它节点发起新的一轮选举
            // Upon election: send initial empty AppendEntries RPCs (heartbeat) to each server;
            // repeat during idle periods to prevent election timeouts (§5.2)
            HeartbeatBroadcastTask.doHeartbeatBroadcast(currentServer);
        }else{
            // 票数不过半，无法成为leader
            logger.info("HeartbeatTimeoutCheck election result: not become a leader! {}",currentServer.getServerId());
        }

        this.currentServer.cleanVotedFor();
    }
}
