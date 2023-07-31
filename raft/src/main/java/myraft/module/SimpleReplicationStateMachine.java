package myraft.module;

import myraft.RaftServer;
import myraft.api.command.EmptySetCommand;
import myraft.api.command.SetCommand;
import myraft.module.api.KVReplicationStateMachine;
import myraft.module.model.LocalLogEntry;
import myraft.util.util.MyRaftFileUtil;
import myrpc.common.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * 简易复制状态机(持久化到磁盘中的最基础的k/v数据库)
 * 简单起见：内存中是一个k/v Map，每次写请求都全量写入磁盘
 * */
public class SimpleReplicationStateMachine implements KVReplicationStateMachine {
    private static final Logger logger = LoggerFactory.getLogger(SimpleReplicationStateMachine.class);

    private final ConcurrentHashMap<String,String> kvMap;

    private final File persistenceFile;

    private final ReentrantReadWriteLock reentrantLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = reentrantLock.writeLock();
    private final ReentrantReadWriteLock.ReadLock readLock = reentrantLock.readLock();

    public SimpleReplicationStateMachine(RaftServer raftServer){
        String userPath = System.getProperty("user.dir") + File.separator + raftServer.getServerId();

        this.persistenceFile = new File(userPath + File.separator + "raftReplicationStateMachine-" + raftServer.getServerId() + ".txt");
        MyRaftFileUtil.createFile(persistenceFile);

        kvMap = new ConcurrentHashMap<>();
        MyRaftFileUtil.writeInFile(persistenceFile, JsonUtil.obj2Str(kvMap));

        List<LocalLogEntry> logEntryList = raftServer.getLogModule().readLocalLog(
            -1,raftServer.getLogModule().getLastIndex());
        List<SetCommand> setCommandList = logEntryList.stream()
            .filter(item->item.getCommand() instanceof SetCommand)
            .map(item->(SetCommand)item.getCommand()).collect(Collectors.toList());

        // 状态机启动时不以持久化文件中的数据为准，而是重新执行一遍已提交的raft日志
        batchApply(setCommandList);
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
    public void batchApply(List<SetCommand> setCommandList) {
        writeLock.lock();
        try{

            setCommandList = setCommandList.stream()
                // 过滤掉no-op操作
                .filter(item->!(item instanceof EmptySetCommand))
                .collect(Collectors.toList());

            logger.info("batchApply setCommand start,size={}",setCommandList.size());

            for(SetCommand setCommand : setCommandList){
                logger.info("apply setCommand start,{}",setCommand);
                kvMap.put(setCommand.getKey(), setCommand.getValue());
            }

            // 持久化(简单起见，暂时不考虑性能问题)
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

    /**
     * 用于单元测试
     * */
    public void clean() {
        System.out.println("SimpleReplicationStateMachine clean!");
        this.persistenceFile.delete();
    }
}
