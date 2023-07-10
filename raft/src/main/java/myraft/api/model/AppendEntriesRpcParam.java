package myraft.api.model;

/**
 * 追加日志条目的RPC接口参数对象
 * */
public class AppendEntriesRpcParam {

    /**
     * 当前leader的任期值
     * */
    private int term;

    /**
     * leader的id
     * */
    private int leaderId;

    public int getTerm() {
        return term;
    }

    public void setTerm(int term) {
        this.term = term;
    }

    public int getLeaderId() {
        return leaderId;
    }

    public void setLeaderId(int leaderId) {
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
