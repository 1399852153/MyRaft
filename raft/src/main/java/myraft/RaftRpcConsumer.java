package myraft;

import myraft.api.model.AppendEntriesRpcParam;
import myraft.api.model.AppendEntriesRpcResult;
import myraft.api.model.RequestVoteRpcParam;
import myraft.api.model.RequestVoteRpcResult;
import myraft.api.service.RaftService;
import myraft.common.config.RaftNodeConfig;
import myrpc.common.model.URLAddress;
import myrpc.consumer.context.ConsumerRpcContext;
import myrpc.consumer.context.ConsumerRpcContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


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

    @Override
    public String toString() {
        return "RaftRpcConsumer{" +
            "targetNodeConfig=" + targetNodeConfig +
            '}';
    }
}
