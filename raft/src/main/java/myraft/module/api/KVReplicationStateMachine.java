package myraft.module.api;

import myraft.api.command.SetCommand;

import java.util.List;

/**
 * K/V 复制状态机
 * */
public interface KVReplicationStateMachine {

    /**
     * 应用日志条目到状态机中（写操作）
     * */
    void apply(SetCommand setCommand);

    /**
     * 批量写（暂不考虑setCommandList过多的问题）
     * */
    void batchApply(List<SetCommand> setCommandList);

    /**
     * 简单起见，直接提供一个读的方法，而不是另外用别的模块来做
     * */
    String get(String key);

}
