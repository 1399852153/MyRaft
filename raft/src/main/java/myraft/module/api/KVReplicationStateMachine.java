package myraft.module.api;

import myraft.api.command.SetCommand;

/**
 * K/V 复制状态机
 * */
public interface KVReplicationStateMachine {

    /**
     * 应用日志条目到状态机中（写操作）
     * */
    void apply(SetCommand setCommand);

    /**
     * 简单起见，直接提供一个读的方法，而不是另外用别的模块来做
     * */
    String get(String key);

    /**
     * 安装快照
     * */
    void installSnapshot(byte[] snapshot);

    /**
     * 构建并返回当前状态机快照
     * */
    byte[] buildSnapshot();

}
