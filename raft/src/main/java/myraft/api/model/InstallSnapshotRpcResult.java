package myraft.api.model;

import java.io.Serializable;

public class InstallSnapshotRpcResult implements Serializable {

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
