package myraft.module;

import myraft.common.model.RaftServerMetaData;
import myraft.util.util.MyRaftFileUtil;
import myrpc.common.util.JsonUtil;
import myrpc.util.StringUtils;

import java.io.File;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class RaftServerMetaDataPersistentModule {

    /**
     * 当前服务器的任期值
     * */
    private volatile int currentTerm;

    /**
     * 当前服务器在此之前投票给了谁？
     * (候选者的serverId，如果还没有投递就是null)
     * */
    private volatile String votedFor;

    private final File persistenceFile;

    private final ReentrantReadWriteLock reentrantLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = reentrantLock.writeLock();
    private final ReentrantReadWriteLock.ReadLock readLock = reentrantLock.readLock();

    public RaftServerMetaDataPersistentModule(String serverId) {
        String userPath = System.getProperty("user.dir") + File.separator + serverId;

        this.persistenceFile = new File(userPath + File.separator + "raftServerMetaData-" + serverId + ".txt");
        MyRaftFileUtil.createFile(persistenceFile);

        // 读取持久化在磁盘中的数据
        RaftServerMetaData raftServerMetaData = readRaftServerMetaData(persistenceFile);
        this.currentTerm = raftServerMetaData.getCurrentTerm();
        this.votedFor = raftServerMetaData.getVotedFor();
    }

    public int getCurrentTerm() {
        readLock.lock();
        try {
            return currentTerm;
        }finally {
            readLock.unlock();
        }
    }

    public void setCurrentTerm(int currentTerm) {
        writeLock.lock();
        try {
            this.currentTerm = currentTerm;

            // 更新后数据落盘
            persistentRaftServerMetaData(new RaftServerMetaData(this.currentTerm,this.votedFor),persistenceFile);
        }finally {
            writeLock.unlock();
        }
    }

    public String getVotedFor() {
        readLock.lock();
        try {
            return votedFor;
        }finally {
            readLock.unlock();
        }
    }

    public void setVotedFor(String votedFor) {
        writeLock.lock();
        try {
            if(Objects.equals(this.votedFor,votedFor)){
                // 相等的话就不刷新了
                return;
            }

            this.votedFor = votedFor;

            // 更新后数据落盘
            persistentRaftServerMetaData(new RaftServerMetaData(this.currentTerm,this.votedFor),persistenceFile);
        }finally {
            writeLock.unlock();
        }
    }

    public void refreshRaftServerMetaData(RaftServerMetaData raftServerMetaData){
        writeLock.lock();
        try {
            this.currentTerm = raftServerMetaData.getCurrentTerm();
            this.votedFor = raftServerMetaData.getVotedFor();

            // 更新后数据落盘
            persistentRaftServerMetaData(new RaftServerMetaData(this.currentTerm,this.votedFor),persistenceFile);
        }finally {
            writeLock.unlock();
        }
    }

    private static RaftServerMetaData readRaftServerMetaData(File persistenceFile){
        String content = MyRaftFileUtil.getFileContent(persistenceFile);
        if(StringUtils.hasText(content)){
            return JsonUtil.json2Obj(content,RaftServerMetaData.class);
        }else{
            return RaftServerMetaData.getDefault();
        }
    }

    private static void persistentRaftServerMetaData(RaftServerMetaData raftServerMetaData, File persistenceFile){
        String content = JsonUtil.obj2Str(raftServerMetaData);

        MyRaftFileUtil.writeInFile(persistenceFile,content);
    }
}
