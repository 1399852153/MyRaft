package myraft.rpc;

import myraft.RaftClient;
import myraft.api.command.GetCommand;
import myraft.api.command.SetCommand;
import myraft.rpc.config.RaftClusterGlobalConfig;
import myrpc.exchange.DefaultFuture;

import java.util.Objects;
import java.util.Scanner;

/**
 * 命令行交互的客户端
 *
 * 只支持以下命令
 * 1. get [key]
 * 2. set [key] [value]
 * 3. quit
 * */
public class RpcCmdInteractiveClient {

    public static void main(String[] args) {
        // 客户端的超时时间必须大于raft内部rpc的超时时间，否则在节点故障时rpc会一直超时
        DefaultFuture.DEFAULT_TIME_OUT = 3000L;

        RaftClient raftClient = new RaftClient(RaftClusterGlobalConfig.registry);
        raftClient.init();
        raftClient.setRaftNodeConfigList(RaftClusterGlobalConfig.raftNodeConfigList);

        Scanner scan = new Scanner(System.in);

        System.out.println("RpcCmdInteractiveClient start, please input command:");

        while(scan.hasNext()) {
            String input = scan.nextLine();
            if(input.length() == 0){
                continue;
            }

            if (Objects.equals(input, "quit")) {
                scan.close();
                System.out.println("RpcCmdInteractiveClient quit success!");
                return;
            }

            if (input.startsWith("get")) {
                processGetCmd(raftClient,input);
            }else if(input.startsWith("set")){
                processSetCmd(raftClient,input);
            }else{
                System.out.println("un support cmd, please retry！");
            }
        }
    }

    private static void processGetCmd(RaftClient raftClient, String input){
        try {
            String[] cmdItem = input.split(" ");
            if (cmdItem.length != 2) {
                System.out.println("get cmd error, please retry！");
                return;
            }

            String key = cmdItem[1];
            String result = raftClient.doRequestRetry(new GetCommand(key),2);
            System.out.println("processGet result=" + result);
        }catch (Exception e){
            System.out.println("processGet error!");
            e.printStackTrace();
        }
    }

    private static void processSetCmd(RaftClient raftClient, String input){
        try {
            String[] cmdItem = input.split(" ");
            if (cmdItem.length != 3) {
                System.out.println("set cmd error, please retry！");
                return;
            }

            String key = cmdItem[1];
            String value = cmdItem[2];
            String result = raftClient.doRequestRetry(new SetCommand(key, value),2);
            System.out.println("processSet success=" + result);
        }catch (Exception e){
            System.out.println("processSetCmd error!");
            e.printStackTrace();
        }
    }
}
