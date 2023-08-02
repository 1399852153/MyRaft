package myraft.rpc;

import myraft.RaftClient;
import myraft.api.command.GetCommand;
import myraft.api.command.SetCommand;
import myraft.rpc.config.RaftClusterGlobalConfig;
import myrpc.exchange.DefaultFuture;
import org.junit.Assert;

public class RpcClientDemo {


    public static void main(String[] args) {
        DefaultFuture.DEFAULT_TIME_OUT = 3000L;
        RaftClient raftClient = new RaftClient(RaftClusterGlobalConfig.registry);
        raftClient.init();
        raftClient.setRaftNodeConfigList(RaftClusterGlobalConfig.raftNodeConfigList);

        System.out.println(raftClient.doRequest(new GetCommand("k1")));

        {
            raftClient.doRequest(new SetCommand("k1", "v1"));

            String result = raftClient.doRequest(new GetCommand("k1"));
            Assert.assertEquals(result, "v1");
        }

        System.out.println("all finished!");

    }
}
