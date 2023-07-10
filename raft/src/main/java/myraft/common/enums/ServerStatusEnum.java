package myraft.common.enums;

/**
 * Raft服务器节点状态类型的枚举
 * */
public enum ServerStatusEnum {

    FOLLOWER("follower-追随者"),
    CANDIDATE("candidate-候选者"),
    LEADER("leader-领导者"),
    ;

    ServerStatusEnum(String message) {
        this.message = message;
    }

    private final String message;

    public String getMessage() {
        return message;
    }
}
