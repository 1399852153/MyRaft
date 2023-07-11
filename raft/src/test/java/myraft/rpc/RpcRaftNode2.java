package myraft.rpc;

import myraft.rpc.config.RaftClusterGlobalConfig;

public class RpcRaftNode2 {

    /**
     * rpc raft
     * */
    public static void main(String[] args) {
        RaftClusterGlobalConfig.initRaftRpcServer("raft-2");
    }
}
