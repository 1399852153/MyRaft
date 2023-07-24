package myraft.module;

import com.fasterxml.jackson.core.type.TypeReference;
import myraft.api.command.EmptySetCommand;
import myraft.api.command.SetCommand;
import myraft.module.api.KVReplicationStateMachine;
import myraft.util.util.MyRaftFileUtil;
import myrpc.common.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 简易复制状态机(持久化到磁盘中的最基础的k/v数据库)
 * 简单起见：内存中是一个k/v Map，每次写请求都全量写入磁盘
 * */
public class SimpleReplicationStateMachine implements KVReplicationStateMachine {
    private static final Logger logger = LoggerFactory.getLogger(SimpleReplicationStateMachine.class);

    private volatile ConcurrentHashMap<String,String> kvMap;

    private final File persistenceFile;

    private final ReentrantReadWriteLock reentrantLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = reentrantLock.writeLock();
    private final ReentrantReadWriteLock.ReadLock readLock = reentrantLock.readLock();

    public SimpleReplicationStateMachine(String serverId){
        String userPath = System.getProperty("user.dir") + File.separator + serverId;

        this.persistenceFile = new File(userPath + File.separator + "raftReplicationStateMachine-" + serverId + ".txt");
        MyRaftFileUtil.createFile(persistenceFile);

        // 状态机启动时不以持久化文件中的数据为准，而是
        kvMap = new ConcurrentHashMap<>();
    }

    @Override
    public void apply(SetCommand setCommand) {
        if(setCommand instanceof EmptySetCommand){
            // no-op，状态机无需做任何操作
            logger.info("apply EmptySetCommand quick return!");
            return;
        }

        logger.info("apply setCommand start,{}",setCommand);
        writeLock.lock();
        try {
            kvMap.put(setCommand.getKey(), setCommand.getValue());

            // 每次写操作完都持久化一遍(简单起见，暂时不考虑性能问题)
            MyRaftFileUtil.writeInFile(persistenceFile, JsonUtil.obj2Str(kvMap));
            logger.info("apply setCommand end");
        }finally {
            writeLock.unlock();
        }
    }

    @Override
    public String get(String key) {
        readLock.lock();

        try {
            return kvMap.get(key);
        }finally {
            readLock.unlock();
        }
    }

    @Override
    public void installSnapshot(byte[] snapshot) {
        writeLock.lock();

        try {
            // 简单起见，一把梭
            String mapJson = new String(snapshot, StandardCharsets.UTF_8);
            this.kvMap = JsonUtil.json2Obj(mapJson, new TypeReference<ConcurrentHashMap<String, String>>() {});
        }finally {
            writeLock.unlock();
        }
    }

    @Override
    public byte[] buildSnapshot() {
        readLock.lock();

        try {
            String mapJson = JsonUtil.obj2Str(kvMap);
            return mapJson.getBytes(StandardCharsets.UTF_8);
        }finally {
            readLock.unlock();
        }
    }

    /**
     * 用于单元测试
     * */
    public void clean() {
        System.out.println("SimpleReplicationStateMachine clean!");
        this.persistenceFile.delete();
    }
}
