package myraft.api.model;

/**
 * 请求投票的RPC接口参数对象
 */
public class RequestVoteRpcParam {

    /**
     * 候选人的任期编号
     * */
    private int term;

    /**
     * 候选人的Id
     * */
    private String candidateId;

    /**
     * 候选人最新日志的索引编号
     * */
    private long lastLogIndex;

    /**
     * 候选人最新日志对应的任期编号
     * */
    private int lastLogTerm;

    public int getTerm() {
        return term;
    }

    public void setTerm(int term) {
        this.term = term;
    }

    public String getCandidateId() {
        return candidateId;
    }

    public void setCandidateId(String candidateId) {
        this.candidateId = candidateId;
    }

    public long getLastLogIndex() {
        return lastLogIndex;
    }

    public void setLastLogIndex(long lastLogIndex) {
        this.lastLogIndex = lastLogIndex;
    }

    public int getLastLogTerm() {
        return lastLogTerm;
    }

    public void setLastLogTerm(int lastLogTerm) {
        this.lastLogTerm = lastLogTerm;
    }

    @Override
    public String toString() {
        return "RequestVoteParam{" +
            "term=" + term +
            ", candidateId=" + candidateId +
            ", lastLogIndex=" + lastLogIndex +
            ", lastLogTerm=" + lastLogTerm +
            '}';
    }
}
