package myraft.api.model;

import java.io.Serializable;

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

    @Override
    public String toString() {
        return "AppendEntriesRpcParam{" +
                "term=" + term +
                ", leaderId=" + leaderId +
                '}';
    }
}
