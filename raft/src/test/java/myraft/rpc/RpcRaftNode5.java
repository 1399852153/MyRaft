package myraft.rpc;

import myraft.rpc.config.RaftClusterGlobalConfig;

public class RpcRaftNode5 {

    /**
     * rpc raft
     * */
    public static void main(String[] args) {
        RaftClusterGlobalConfig.initRaftRpcServer("raft-5");
    }
}
