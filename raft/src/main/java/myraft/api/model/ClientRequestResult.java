package myraft.api.model;


import java.io.Serializable;

public class ClientRequestResult implements Serializable {

    /**
     * get读请求的返回值，set写请求时为null
     * */
    private String value;

    /**
     * 如果请求的节点不是leader，该节点会返回它认为的当前leader的服务地址
     * */
    private URLAddress leaderAddress;

    /**
     * 响应是否成功
     * */
    private boolean success;

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public URLAddress getLeaderAddress() {
        return leaderAddress;
    }

    public void setLeaderAddress(URLAddress leaderAddress) {
        this.leaderAddress = leaderAddress;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    @Override
    public String toString() {
        return "ClientRequestResult{" +
            "value='" + value + '\'' +
            ", leaderAddress=" + leaderAddress +
            ", success=" + success +
            '}';
    }
}
