package myraft.common.model;

public class RaftServerMetaData {

    /**
     * 当前服务器的任期值
     * */
    private int currentTerm;

    /**
     * 当前服务器在此之前投票给了谁？
     * (候选者的serverId，如果还没有投递就是null)
     * */
    private Integer votedFor;

    public RaftServerMetaData() {
    }

    public RaftServerMetaData(int currentTerm, Integer votedFor) {
        this.currentTerm = currentTerm;
        this.votedFor = votedFor;
    }

    public int getCurrentTerm() {
        return currentTerm;
    }

    public void setCurrentTerm(int currentTerm) {
        this.currentTerm = currentTerm;
    }

    public Integer getVotedFor() {
        return votedFor;
    }

    public void setVotedFor(Integer votedFor) {
        this.votedFor = votedFor;
    }

    public static RaftServerMetaData getDefault(){
        return new RaftServerMetaData(0,null);
    }
}
