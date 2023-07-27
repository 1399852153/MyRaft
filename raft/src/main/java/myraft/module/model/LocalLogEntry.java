package myraft.module.model;

import myraft.api.model.LogEntry;

/**
 * logEntry在本地的拓展对象
 * */
public class LocalLogEntry extends LogEntry {

    /**
     * 该日志时的末尾位置
     * */
    private long startOffset;

    /**
     * 该日志时的末尾位置
     * */
    private long endOffset;

    public long getStartOffset() {
        return startOffset;
    }

    public void setStartOffset(long startOffset) {
        this.startOffset = startOffset;
    }

    public long getEndOffset() {
        return endOffset;
    }

    public void setEndOffset(long endOffset) {
        this.endOffset = endOffset;
    }

    @Override
    public String toString() {
        return "LocalLogEntry{" +
            "preOffset=" + startOffset +
            ", endOffset=" + endOffset +
            "} " + super.toString();
    }
}
