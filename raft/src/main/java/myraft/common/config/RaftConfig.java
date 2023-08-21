package myraft.common.config;

import myraft.util.util.Range;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class RaftConfig {

    private static final Logger logger = LoggerFactory.getLogger(RaftConfig.class);

    /**
     * 当前服务节点的id(集群内全局唯一)
     * */
    private final String serverId;

    /**
     * 自己节点的配置
     * */
    private final RaftNodeConfig currentNodeConfig;

    /**
     * 整个集群所有的服务节点的id集合
     * */
    private final List<RaftNodeConfig> raftNodeConfigList;

    private final int majorityNum;

    /**
     * 选举超时时间 单位:秒
     * */
    private int electionTimeout;

    /**
     * debug的时候把这个值设置的长一点，避免阻塞时触发了新的选举(只用于debug)
     * */
    private Integer debugElectionTimeout;

    /**
     * 选举超时时间的随机化区间 单位：毫秒
     * */
    private Range<Integer> electionTimeoutRandomRange;

    /**
     * 心跳间隔时间 单位：秒
     * */
    private int HeartbeatInternal;

    /**
     * leader自动故障计数(用于触发自动选举的调试，和正常逻辑无关)
     * */
    private int leaderAutoFailCount;

    /**
     * appendEntries批量发送日志的条数(必须大于0)
     * */
    private int appendLogEntryBatchNum = 2;

    /**
     * 日志文件生成快照的阈值(单位：byte字节)
     * */
    private long logFileThreshold;

    /**
     * 快照安装的rpc，每次传输的数据块大小
     * */
    private int installSnapshotBlockSize;

    /**
     * 是否开启快照，默认关闭
     * */
    private Boolean snapshotEnable = false;

    public RaftConfig(RaftNodeConfig currentNodeConfig,List<RaftNodeConfig> raftNodeConfigList) {
        this.serverId = currentNodeConfig.getServerId();
        this.currentNodeConfig = currentNodeConfig;
        this.raftNodeConfigList = raftNodeConfigList;
        // 要求集群配置必须是奇数的，偶数的节点个数容错率更差
        // 例如：5个节点的集群可以容忍2个节点故障，而6个节点的集群也只能容忍2个节点故障
        if(!isOddNumber(raftNodeConfigList.size())){
            logger.warn("cluster server size not odd number! size={}",raftNodeConfigList.size());
        }

        this.majorityNum = this.raftNodeConfigList.size()/2 + 1;
    }

    public String getServerId() {
        return serverId;
    }

    public RaftNodeConfig getCurrentNodeConfig() {
        return currentNodeConfig;
    }

    public List<RaftNodeConfig> getRaftNodeConfigList() {
        return raftNodeConfigList;
    }

    public int getMajorityNum() {
        return majorityNum;
    }

    public void setElectionTimeout(int electionTimeout) {
        this.electionTimeout = electionTimeout;
    }

    public int getElectionTimeout() {
        return electionTimeout;
    }

    public Integer getDebugElectionTimeout() {
        return debugElectionTimeout;
    }

    public void setDebugElectionTimeout(Integer debugElectionTimeout) {
        this.debugElectionTimeout = debugElectionTimeout;
    }

    public int getHeartbeatInternal() {
        return HeartbeatInternal;
    }

    public void setHeartbeatInternal(int HeartbeatInternal) {
        this.HeartbeatInternal = HeartbeatInternal;
    }

    public Range<Integer> getElectionTimeoutRandomRange() {
        return electionTimeoutRandomRange;
    }

    public void setElectionTimeoutRandomRange(Range<Integer> electionTimeoutRandomRange) {
        this.electionTimeoutRandomRange = electionTimeoutRandomRange;
    }

    public int getLeaderAutoFailCount() {
        return leaderAutoFailCount;
    }

    public void setLeaderAutoFailCount(int leaderAutoFailCount) {
        this.leaderAutoFailCount = leaderAutoFailCount;
    }

    public int getAppendLogEntryBatchNum() {
        return appendLogEntryBatchNum;
    }

    public void setAppendLogEntryBatchNum(int appendLogEntryBatchNum) {
        this.appendLogEntryBatchNum = appendLogEntryBatchNum;
    }

    public long getLogFileThreshold() {
        return logFileThreshold;
    }

    public void setLogFileThreshold(long logFileThreshold) {
        this.logFileThreshold = logFileThreshold;
    }

    public int getInstallSnapshotBlockSize() {
        return installSnapshotBlockSize;
    }

    public void setInstallSnapshotBlockSize(int installSnapshotBlockSize) {
        this.installSnapshotBlockSize = installSnapshotBlockSize;
    }

    public Boolean getSnapshotEnable() {
        return snapshotEnable;
    }

    public void setSnapshotEnable(Boolean snapshotEnable) {
        this.snapshotEnable = snapshotEnable;
    }

    private boolean isOddNumber(int num){
        return num % 2 == 1;
    }
}
