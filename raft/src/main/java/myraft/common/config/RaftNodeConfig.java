package myraft.common.config;

public class RaftNodeConfig {

    private String serverId;
    private String ip;
    private int port;

    public RaftNodeConfig(String serverId) {
        this.serverId = serverId;
    }

    public RaftNodeConfig(String serverId, String ip, int port) {
        this.serverId = serverId;
        this.ip = ip;
        this.port = port;
    }

    public String getServerId() {
        return serverId;
    }

    public String getIp() {
        return ip;
    }

    public int getPort() {
        return port;
    }

    @Override
    public String toString() {
        return "RaftNodeConfig{" +
            "serverId=" + serverId +
            ", ip='" + ip + '\'' +
            ", port=" + port +
            '}';
    }
}
