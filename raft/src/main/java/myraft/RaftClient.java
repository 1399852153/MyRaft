package myraft;

import myraft.api.command.Command;
import myraft.api.model.ClientRequestParam;
import myraft.api.model.ClientRequestResult;
import myraft.api.service.RaftService;
import myraft.common.config.RaftNodeConfig;
import myrpc.balance.LoadBalance;
import myrpc.balance.SimpleRoundRobinBalance;
import myrpc.common.model.ServiceInfo;
import myrpc.common.model.URLAddress;
import myrpc.consumer.Consumer;
import myrpc.consumer.ConsumerBootstrap;
import myrpc.consumer.context.ConsumerRpcContext;
import myrpc.consumer.context.ConsumerRpcContextHolder;
import myrpc.exception.MyRpcException;
import myrpc.invoker.impl.FastFailInvoker;
import myrpc.registry.Registry;

import java.util.List;
import java.util.stream.Collectors;

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
