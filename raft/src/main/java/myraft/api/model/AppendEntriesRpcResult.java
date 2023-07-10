package myraft.api.model;

/**
 * 追加日志条目的RPC接口响应对象
 * */
public class AppendEntriesRpcResult {

    /**
     * 被调用者当前的任期值
     * */
    private int term;

    /**
     * 是否处理成功
     * */
    private boolean success;

    public AppendEntriesRpcResult() {
    }

    public AppendEntriesRpcResult(int term, boolean success) {
        this.term = term;
        this.success = success;
    }

    public int getTerm() {
        return term;
    }

    public void setTerm(int term) {
        this.term = term;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    @Override
    public String toString() {
        return "AppendEntriesRpcResult{" +
                "term=" + term +
                ", success=" + success +
                '}';
    }
}
