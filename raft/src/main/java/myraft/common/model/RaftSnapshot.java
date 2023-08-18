package myraft.common.model;

/**
 * raft快照对象
 * */
public class RaftSnapshot {

    /**
     * 快照所包含的最后一条log的索引编号
     * */
    private long lastIncludedIndex;

    /**
     * 快照所包含的最后一条log的任期编号
     * */
    private int lastIncludedTerm;

    /**
     * 快照数据
     * (注意：暂不考虑快照过大导致byte数组放不下，需要读取多次的情况)
     * */
    private byte[] snapshotData = new byte[0];

    public long getLastIncludedIndex() {
        return lastIncludedIndex;
    }

    public void setLastIncludedIndex(long lastIncludedIndex) {
        this.lastIncludedIndex = lastIncludedIndex;
    }

    public int getLastIncludedTerm() {
        return lastIncludedTerm;
    }

    public void setLastIncludedTerm(int lastIncludedTerm) {
        this.lastIncludedTerm = lastIncludedTerm;
    }

    public byte[] getSnapshotData() {
        return snapshotData;
    }

    public void setSnapshotData(byte[] snapshotData) {
        this.snapshotData = snapshotData;
    }

    @Override
    public String toString() {
        return "RaftSnapshot{" +
            "lastIncludedIndex=" + lastIncludedIndex +
            ", lastIncludedTerm=" + lastIncludedTerm +
            ", snapshotData.length=" + snapshotData.length +
            '}';
    }
}
