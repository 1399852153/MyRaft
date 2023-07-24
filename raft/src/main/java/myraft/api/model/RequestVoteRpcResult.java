package myraft.api.model;

/**
 * 请求投票的RPC接口响应对象
 * */
public class RequestVoteRpcResult {

    /**
     * 被调用者当前的任期值
     * */
    private int term;

    /**
     * 是否同意投票给调用者
     * */
    private boolean voteGranted;

    public RequestVoteRpcResult() {
    }

    public RequestVoteRpcResult(int term, boolean voteGranted) {
        this.term = term;
        this.voteGranted = voteGranted;
    }

    public int getTerm() {
        return term;
    }

    public void setTerm(int term) {
        this.term = term;
    }

    public boolean isVoteGranted() {
        return voteGranted;
    }

    public void setVoteGranted(boolean voteGranted) {
        this.voteGranted = voteGranted;
    }

    @Override
    public String toString() {
        return "RequestVoteRpcResult{" +
            "term=" + term +
            ", voteGranted=" + voteGranted +
            '}';
    }
}
