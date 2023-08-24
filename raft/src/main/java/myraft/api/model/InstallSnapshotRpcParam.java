package myraft.api.model;

import java.io.Serializable;

public class InstallSnapshotRpcParam implements Serializable {

    /**
     * 当前leader的任期值
     * */
    private int term;

    /**
     * leader的id
     * */
    private String leaderId;

    /**
     * 快照所包含的最后一条log的索引编号
     * */
    private long lastIncludedIndex;

    /**
     * 快照所包含的最后一条log的任期编号
     * */
    private int lastIncludedTerm;

    /**
     * 当前传输的data，在整个快照中的偏移量
     * (快照很大，需要分多次传输)
     * */
    private long offset;

    /**
     * 快照片段数据
     * */
    private byte[] data;

    /**
     * 当前传输的快照片段是否是最后一段
     * */
    private boolean done;

    public int getTerm() {
        return term;
    }

    public void setTerm(int term) {
        this.term = term;
    }

    public String getLeaderId() {
        return leaderId;
    }

    public void setLeaderId(String leaderId) {
        this.leaderId = leaderId;
    }

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

    public long getOffset() {
        return offset;
    }

    public void setOffset(long offset) {
        this.offset = offset;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    public boolean isDone() {
        return done;
    }

    public void setDone(boolean done) {
        this.done = done;
    }

    @Override
    public String toString() {
        return "InstallSnapshotRpcParam{" +
            "term=" + term +
            ", leaderId=" + leaderId +
            ", lastIncludedIndex=" + lastIncludedIndex +
            ", lastIncludedTerm=" + lastIncludedTerm +
            ", offset=" + offset +
            ", done=" + done +
            '}';
    }
}
