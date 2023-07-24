package myraft.api.model;


import myraft.api.command.Command;

/**
 * raft日志条目
 * */
public class LogEntry {

    /**
     * 发布日志时的leader的任期编号
     * */
    private int logTerm;

    /**
     * 日志的索引编号
     * */
    private long logIndex;

    /**
     * 具体作用在状态机上的指令
     * */
    private Command command;

    public int getLogTerm() {
        return logTerm;
    }

    public void setLogTerm(int logTerm) {
        this.logTerm = logTerm;
    }

    public long getLogIndex() {
        return logIndex;
    }

    public void setLogIndex(long logIndex) {
        this.logIndex = logIndex;
    }

    public Command getCommand() {
        return command;
    }

    public void setCommand(Command command) {
        this.command = command;
    }

    public static LogEntry getEmptyLogEntry(){
        LogEntry logEntry = new LogEntry();
        logEntry.setLogTerm(-1);
        logEntry.setLogIndex(-1);

        return logEntry;
    }

    @Override
    public String toString() {
        return "LogEntry{" +
            "logTerm=" + logTerm +
            ", logIndex=" + logIndex +
            ", command=" + command +
            '}';
    }
}
