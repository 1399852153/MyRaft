package myraft.module;

import myraft.RaftServer;
import myraft.task.task.HeartBeatBroadcastTask;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Raft服务器的心跳广播模块
 * */
public class RaftHeartBeatBroadcastModule {

    private final RaftServer currentServer;

    private final ScheduledExecutorService scheduledExecutorService;

    private final ExecutorService rpcThreadPool;

    public RaftHeartBeatBroadcastModule(RaftServer currentServer) {
        this.currentServer = currentServer;

        this.scheduledExecutorService = Executors.newScheduledThreadPool(1);
        this.rpcThreadPool = Executors.newFixedThreadPool(
            Math.max(currentServer.getOtherNodeInCluster().size() * 2,1));

        int heartbeatInternal = currentServer.getRaftConfig().getHeartbeatInternal();

        // 心跳广播任务需要以固定频率执行(scheduleAtFixedRate)
        scheduledExecutorService.scheduleAtFixedRate(
            new HeartBeatBroadcastTask(currentServer,this),0,heartbeatInternal, TimeUnit.SECONDS);
    }

    public RaftServer getCurrentServer() {
        return currentServer;
    }

    public ExecutorService getRpcThreadPool() {
        return rpcThreadPool;
    }
}
