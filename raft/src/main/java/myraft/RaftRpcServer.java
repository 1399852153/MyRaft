package myraft;

import myraft.api.service.RaftService;
import myraft.common.config.RaftConfig;
import myraft.common.config.RaftNodeConfig;
import myrpc.balance.SimpleRoundRobinBalance;
import myrpc.common.model.URLAddress;
import myrpc.consumer.Consumer;
import myrpc.consumer.ConsumerBootstrap;
import myrpc.invoker.impl.FastFailInvoker;
import myrpc.netty.server.NettyServer;
import myrpc.provider.Provider;
import myrpc.registry.Registry;

import java.util.ArrayList;
import java.util.List;

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
