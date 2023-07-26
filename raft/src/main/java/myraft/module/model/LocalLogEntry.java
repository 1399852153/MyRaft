package myraft.module.model;

import myraft.api.model.LogEntry;

/**
 * logEntry在本地的拓展对象
 * */
public class LocalLogEntry extends LogEntry {

    /**
     * 相当于是写入该日志时的起始位置(便于定位到日志)
     * */
    private long offset;

    public long getOffset() {
        return offset;
    }

    public void setOffset(long offset) {
        this.offset = offset;
    }
}
