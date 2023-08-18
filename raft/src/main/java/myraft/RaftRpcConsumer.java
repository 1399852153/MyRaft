package myraft;

import myraft.api.model.*;
import myraft.api.service.RaftService;
import myraft.common.config.RaftNodeConfig;
import myraft.exception.MyRaftException;
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
    public ClientRequestResult clientRequest(ClientRequestParam clientRequestParam) {
        // 只有raft的客户端才会调用这个方法，服务端是不会调用这个方法的
        throw new MyRaftException("raft server node can not be invoke clientRequest!");
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

    @Override
    public InstallSnapshotRpcResult installSnapshot(InstallSnapshotRpcParam installSnapshotRpcParam) {
        // 强制指定rpc目标的ip/port
        setTargetProviderUrl();
        InstallSnapshotRpcResult result = raftServiceProxy.installSnapshot(installSnapshotRpcParam);
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
