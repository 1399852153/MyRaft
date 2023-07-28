package myraft.api.model;

import java.io.Serializable;
import java.util.List;

/**
 * 追加日志条目的RPC接口参数对象
 * */
public class AppendEntriesRpcParam implements Serializable {

    /**
     * 当前leader的任期值
     * */
    private int term;

    /**
     * leader的id
     * */
    private String leaderId;

    /**
     * 当前请求日志条目的前一个日志的索引编号
     *
     * index of log entry immediately preceding new ones
     * */
    private long prevLogIndex;

    /**
     * prevLogIndex对应日志条目的任期值
     *
     * term of prevLogIndex entry
     * */
    private int prevLogTerm;

    /**
     * 本次请求需要追加的新日志条目
     * */
    private List<LogEntry> entries;

    /**
     * leader当前已提交的日志索引
     *
     * leader’s commitIndex
     * */
    private long leaderCommit;

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

    public long getPrevLogIndex() {
        return prevLogIndex;
    }

    public void setPrevLogIndex(long prevLogIndex) {
        this.prevLogIndex = prevLogIndex;
    }

    public int getPrevLogTerm() {
        return prevLogTerm;
    }

    public void setPrevLogTerm(int prevLogTerm) {
        this.prevLogTerm = prevLogTerm;
    }

    public List<LogEntry> getEntries() {
        return entries;
    }

    public void setEntries(List<LogEntry> entries) {
        this.entries = entries;
    }

    public long getLeaderCommit() {
        return leaderCommit;
    }

    public void setLeaderCommit(long leaderCommit) {
        this.leaderCommit = leaderCommit;
    }

    @Override
    public String toString() {
        return "AppendEntriesRpcParam{" +
                "term=" + term +
                ", leaderId=" + leaderId +
                ", prevLogIndex=" + prevLogIndex +
                ", prevLogTerm=" + prevLogTerm +
                ", entries=" + entries +
                ", leaderCommit=" + leaderCommit +
                '}';
    }
}
