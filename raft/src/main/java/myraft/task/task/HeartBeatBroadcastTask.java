package myraft.task.task;

import myraft.RaftServer;
import myraft.api.model.AppendEntriesRpcParam;
import myraft.api.model.AppendEntriesRpcResult;
import myraft.api.service.RaftService;
import myraft.common.enums.ServerStatusEnum;
import myraft.module.RaftHeartBeatBroadcastModule;
import myraft.util.util.CommonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * leader心跳广播任务
 * */
public class HeartBeatBroadcastTask implements Runnable{

    private static final Logger logger = LoggerFactory.getLogger(HeartBeatBroadcastTask.class);

    private final RaftServer currentServer;
    private final RaftHeartBeatBroadcastModule raftHeartBeatBroadcastModule;

    private int heartbeatCount = 0;

    public HeartBeatBroadcastTask(RaftServer currentServer, RaftHeartBeatBroadcastModule raftHeartBeatBroadcastModule) {
        this.currentServer = currentServer;
        this.raftHeartBeatBroadcastModule = raftHeartBeatBroadcastModule;
    }

    @Override
    public void run() {
        if(currentServer.getServerStatusEnum() != ServerStatusEnum.LEADER){
            // 只有leader才需要广播心跳
            return;
        }

        // 心跳广播
        doHeartBeatBroadcast(currentServer);

        processAutoFail();

        logger.debug("do HeartBeatBroadcast end {}",currentServer.getServerId());
    }

    /**
     * 做心跳广播
     * @return 是否大多数节点依然认为自己是leader
     * */
    public static boolean doHeartBeatBroadcast(RaftServer currentServer){
        logger.info("do HeartBeatBroadcast start {}",currentServer.getServerId());

        // 先刷新自己的心跳时间
        currentServer.getRaftLeaderElectionModule().refreshLastHeartbeatTime();

        // 并行的发送心跳rpc给集群中的其它节点
        List<RaftService> otherNodeInCluster = currentServer.getOtherNodeInCluster();
        List<Future<AppendEntriesRpcResult>> futureList = new ArrayList<>(otherNodeInCluster.size());

        // 构造请求参数(心跳rpc，entries为空)
        AppendEntriesRpcParam appendEntriesRpcParam = new AppendEntriesRpcParam();
        appendEntriesRpcParam.setTerm(currentServer.getCurrentTerm());
        appendEntriesRpcParam.setLeaderId(currentServer.getServerId());

        for(RaftService node : otherNodeInCluster){
            Future<AppendEntriesRpcResult> future = currentServer.getRaftHeartBeatBroadcastModule().getRpcThreadPool().submit(
                ()-> {
                    AppendEntriesRpcResult rpcResult = node.appendEntries(appendEntriesRpcParam);
                    // rpc交互时任期高于当前节点任期的处理
                    currentServer.processCommunicationHigherTerm(rpcResult.getTerm());
                    return rpcResult;
                }
            );

            futureList.add(future);
        }

        List<AppendEntriesRpcResult> appendEntriesRpcResultList = CommonUtil.concurrentGetRpcFutureResult("doHeartBeatBroadcast",futureList,
            currentServer.getRaftHeartBeatBroadcastModule().getRpcThreadPool(),1, TimeUnit.SECONDS);

        // 通知成功的数量(+1包括自己)
        int successResponseCount = (int) (appendEntriesRpcResultList.stream().filter(AppendEntriesRpcResult::isSuccess).count() + 1);
        if(successResponseCount >= currentServer.getRaftConfig().getMajorityNum()
            && currentServer.getServerStatusEnum() == ServerStatusEnum.LEADER){
            // 大多数节点依然认为自己是leader,并且广播的节点中没有人任期高于当前节点，让当前节点主动让位
            return true;
        }else{
            // 大多数节点不认为自己是leader（包括广播超时等未接到响应的场景，也认为是广播失败）
            return false;
        }
    }

    /**
     * 用于测试leader故障用的逻辑，和正常逻辑无关
     * */
    private void processAutoFail(){
        int leaderAutoFailCount = currentServer.getRaftConfig().getLeaderAutoFailCount();
        if(leaderAutoFailCount <= 0){
            // 没匹配leader自动故障，直接返回
            return;
        }

        this.heartbeatCount++;
        if(this.heartbeatCount % leaderAutoFailCount == 0){
            logger.info("模拟leader自动故障，转为follower状态从而终止心跳广播，触发新的一轮选举 serverId={}",currentServer.getServerId());
            currentServer.setServerStatusEnum(ServerStatusEnum.FOLLOWER);
            currentServer.setCurrentLeader(null);
        }
    }
}
