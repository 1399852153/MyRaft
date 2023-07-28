package myraft.rpc.config;

import myraft.RaftRpcServer;
import myraft.api.service.RaftService;
import myraft.common.config.RaftConfig;
import myraft.common.config.RaftNodeConfig;
import myraft.exception.MyRaftException;
import myraft.util.util.Range;
import myrpc.registry.Registry;
import myrpc.registry.RegistryConfig;
import myrpc.registry.RegistryFactory;
import myrpc.registry.enums.RegistryCenterTypeEnum;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class RaftClusterGlobalConfig {

    /**
     * 简单起见注册中心统一配置
     * */
    public static Registry registry = RegistryFactory.getRegistry(
        new RegistryConfig(RegistryCenterTypeEnum.FAKE_REGISTRY.getCode(), "127.0.0.1:2181"));

    /**
     * raft的集群配置
     * */
    public static final List<RaftNodeConfig> raftNodeConfigList = Arrays.asList(
        new RaftNodeConfig("raft-1","127.0.0.1",8001)
        ,new RaftNodeConfig("raft-2","127.0.0.1",8002)
//        ,new RaftNodeConfig("raft-3","127.0.0.1",8003)
//        ,new RaftNodeConfig("raft-4","127.0.0.1",8004)
//        ,new RaftNodeConfig("raft-5","127.0.0.1",8005)
    );

    public static final int electionTimeout = 3;

    public static final Integer debugElectionTimeout = null;

    public static final int HeartbeatInterval = 1;

    /**
     * N次心跳后，leader会自动模拟出现故障(退回follow，停止心跳广播)
     * N<=0代表不触发自动模拟故障
     */
    public static final int leaderAutoFailCount = 0;

    /**
     * appendEntries时批量发送的日志条数
     * */
    public static final int appendLogEntryBatchNum = 2;

    /**
     * 随机化的选举超时时间(毫秒)
     * */
    public static final Range<Integer> electionTimeoutRandomRange = new Range<>(150,500);

    public static void initRaftRpcServer(String serverId){
        RaftNodeConfig currentNodeConfig = RaftClusterGlobalConfig.raftNodeConfigList
            .stream().filter(item->item.getServerId().equals(serverId)).findAny()
            .orElseThrow(() -> new MyRaftException("serverId must in raftNodeConfigList"));

        List<RaftNodeConfig> otherNodeList = RaftClusterGlobalConfig.raftNodeConfigList
            .stream().filter(item->!item.getServerId().equals(serverId)).collect(Collectors.toList());

        RaftConfig raftConfig = new RaftConfig(
            currentNodeConfig,RaftClusterGlobalConfig.raftNodeConfigList);
        raftConfig.setElectionTimeout(RaftClusterGlobalConfig.electionTimeout);
        raftConfig.setDebugElectionTimeout(RaftClusterGlobalConfig.debugElectionTimeout);

        raftConfig.setHeartbeatInternal(RaftClusterGlobalConfig.HeartbeatInterval);
        raftConfig.setLeaderAutoFailCount(RaftClusterGlobalConfig.leaderAutoFailCount);
        // 随机化选举超时时间的范围
        raftConfig.setElectionTimeoutRandomRange(RaftClusterGlobalConfig.electionTimeoutRandomRange);
        // appendEntries时批量发送的日志条数
        raftConfig.setAppendLogEntryBatchNum(appendLogEntryBatchNum);

        RaftRpcServer raftRpcServer = new RaftRpcServer(raftConfig, RaftClusterGlobalConfig.registry);
        List<RaftService> raftServiceList = raftRpcServer.getRpcProxyList(otherNodeList);

        // raft服务，启动！
        raftRpcServer.init(raftServiceList);
    }
}
