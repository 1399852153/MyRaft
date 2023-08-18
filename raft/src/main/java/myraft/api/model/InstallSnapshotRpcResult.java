package myraft.api.model;

public class InstallSnapshotRpcResult {

    private int term;

    public InstallSnapshotRpcResult() {
    }

    public InstallSnapshotRpcResult(int term) {
        this.term = term;
    }

    public int getTerm() {
        return term;
    }

    public void setTerm(int term) {
        this.term = term;
    }
}
