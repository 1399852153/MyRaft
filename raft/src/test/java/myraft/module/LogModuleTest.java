package myraft.module;

import myraft.RaftServer;
import myraft.api.command.SetCommand;
import myraft.api.model.LogEntry;
import myraft.common.config.RaftConfig;
import myraft.common.config.RaftNodeConfig;
import myraft.module.model.LocalLogEntry;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class LogModuleTest {

    @Test
    public void test() throws Exception {
        String serverId = "raft-99999";
        RaftNodeConfig raftNodeConfig = new RaftNodeConfig(serverId);
        RaftServer raftServer = new RaftServer(new RaftConfig(raftNodeConfig, Collections.singletonList(raftNodeConfig)));
        raftServer.setOtherNodeInCluster(new ArrayList<>());
        LogModule logModule = new LogModule(raftServer);
        logModule.clean();

        logModule = new LogModule(raftServer);
        {
            LocalLogEntry logEntry = logModule.readLocalLog(0);
            Assert.assertNull(logEntry);
        }

        {
            LocalLogEntry newLogEntry = new LocalLogEntry();
            newLogEntry.setLogIndex(0);
            newLogEntry.setLogTerm(1);
            newLogEntry.setCommand(new SetCommand("k1","v1"));

            logModule.writeLocalLog(newLogEntry);

            LocalLogEntry LocalLogEntry = logModule.readLocalLog(0);
            Assert.assertEquals(LocalLogEntry.getLogIndex(),0);
            Assert.assertEquals(LocalLogEntry.getLogTerm(),1);
            Assert.assertEquals(LocalLogEntry.getStartOffset(),0);
        }

        {
            LocalLogEntry newLogEntry = new LocalLogEntry();
            newLogEntry.setLogIndex(1);
            newLogEntry.setLogTerm(1);
            newLogEntry.setCommand(new SetCommand("k1","v2"));
            logModule.writeLocalLog(newLogEntry);

            LocalLogEntry logEntry = logModule.readLocalLog(1);
            Assert.assertEquals(logEntry.getLogIndex(),1);
            Assert.assertEquals(logEntry.getLogTerm(),1);

            LocalLogEntry logEntry2 = logModule.readLocalLog(2);
            Assert.assertNull(logEntry2);
        }

        {
            LocalLogEntry newLogEntry = new LocalLogEntry();
            newLogEntry.setLogIndex(2);
            newLogEntry.setLogTerm(1);
            newLogEntry.setCommand(new SetCommand("k1","v3"));
            logModule.writeLocalLog(newLogEntry);

            List<LocalLogEntry> logEntryList = logModule.readLocalLog(1,2);

            Assert.assertEquals(logEntryList.get(0).getLogIndex(),1);
            Assert.assertEquals(logEntryList.get(0).getLogTerm(),1);

            Assert.assertEquals(logEntryList.get(1).getLogIndex(),2);
            Assert.assertEquals(logEntryList.get(1).getLogTerm(),1);

        }

        {
            LocalLogEntry newLogEntry = new LocalLogEntry();
            newLogEntry.setLogIndex(3);
            newLogEntry.setLogTerm(1);
            newLogEntry.setCommand(new SetCommand("k1","v4"));
            logModule.writeLocalLog(newLogEntry);

            LogEntry newLogEntry2 = new LogEntry();
            newLogEntry2.setLogIndex(4);
            newLogEntry2.setLogTerm(1);
            newLogEntry2.setCommand(new SetCommand("k1","v5"));
            logModule.writeLocalLog(newLogEntry2);

            List<LocalLogEntry> logEntryList = logModule.readLocalLog(2,5);

            Assert.assertEquals(logEntryList.get(0).getLogIndex(),2);
            Assert.assertEquals(logEntryList.get(0).getLogTerm(),1);

            Assert.assertEquals(logEntryList.get(1).getLogIndex(),3);
            Assert.assertEquals(logEntryList.get(1).getLogTerm(),1);

            Assert.assertEquals(logEntryList.get(2).getLogIndex(),4);
            Assert.assertEquals(logEntryList.get(2).getLogTerm(),1);
        }

        logModule.clean();
    }

    @Test
    public void test2() throws Exception {
        String serverId = "raft-99998";
        RaftNodeConfig raftNodeConfig = new RaftNodeConfig(serverId);
        RaftServer raftServer = new RaftServer(new RaftConfig(raftNodeConfig, Arrays.asList(raftNodeConfig)));
        raftServer.setOtherNodeInCluster(new ArrayList<>());
        LogModule logModule = new LogModule(raftServer);
        logModule.clean();

        logModule = new LogModule(raftServer);

        {
            LogEntry newLogEntry = new LogEntry();
            newLogEntry.setLogIndex(0);
            newLogEntry.setLogTerm(1);
            newLogEntry.setCommand(new SetCommand("k1","v1"));

            logModule.writeLocalLog(newLogEntry);

            LogEntry logEntry = logModule.getLastLogEntry();
            Assert.assertEquals(logEntry.getLogIndex(),0);
            Assert.assertEquals(logEntry.getLogTerm(),1);
        }

        {
            LogEntry newLogEntry = new LogEntry();
            newLogEntry.setLogIndex(1);
            newLogEntry.setLogTerm(1);
            newLogEntry.setCommand(new SetCommand("k1","v2"));

            logModule.writeLocalLog(newLogEntry);

            LogEntry logEntry = logModule.getLastLogEntry();
            Assert.assertEquals(logEntry.getLogIndex(),1);
            Assert.assertEquals(logEntry.getLogTerm(),1);
        }

        {
            LogEntry newLogEntry = new LogEntry();
            newLogEntry.setLogIndex(0);
            newLogEntry.setLogTerm(1);
            newLogEntry.setCommand(new SetCommand("k1","v1"));

            logModule.writeLocalLog(Collections.singletonList(newLogEntry),-1);

            LogEntry logEntry = logModule.getLastLogEntry();
            Assert.assertEquals(logEntry.getLogIndex(),0);
            Assert.assertEquals(logEntry.getLogTerm(),1);

            List<LocalLogEntry> logEntryList = logModule.readLocalLog(0,5);
            Assert.assertEquals(logEntryList.size(),1);
        }

        {
            LogEntry newLogEntry = new LogEntry();
            newLogEntry.setLogIndex(1);
            newLogEntry.setLogTerm(1);
            newLogEntry.setCommand(new SetCommand("k1","v2"));

            logModule.writeLocalLog(Collections.singletonList(newLogEntry));

            LogEntry newLogEntry2 = new LogEntry();
            newLogEntry2.setLogIndex(2);
            newLogEntry2.setLogTerm(2);
            newLogEntry2.setCommand(new SetCommand("k1","v3"));
            logModule.writeLocalLog(Collections.singletonList(newLogEntry2));

            LogEntry newLogEntry3 = new LogEntry();
            newLogEntry3.setLogIndex(3);
            newLogEntry3.setLogTerm(2);
            newLogEntry3.setCommand(new SetCommand("k1","v4"));
            logModule.writeLocalLog(Collections.singletonList(newLogEntry3));

            List<LocalLogEntry> logEntryList = logModule.readLocalLog(0,5);
            Assert.assertEquals(logEntryList.size(),4);
        }

        {
            LogEntry newLogEntry = new LogEntry();
            newLogEntry.setLogIndex(1);
            newLogEntry.setLogTerm(1);
            newLogEntry.setCommand(new SetCommand("k1","v2"));

            LogEntry newLogEntry2 = new LogEntry();
            newLogEntry2.setLogIndex(2);
            newLogEntry2.setLogTerm(1);
            newLogEntry2.setCommand(new SetCommand("k1","v2"));

            logModule.writeLocalLog(Arrays.asList(newLogEntry,newLogEntry2),0);

            List<LocalLogEntry> logEntryList = logModule.readLocalLog(0,2);
            Assert.assertEquals(logEntryList.size(),3);

            Assert.assertEquals(logModule.readLocalLog(0,1).size(),2);
            Assert.assertEquals(logModule.readLocalLog(1,2).size(),2);
        }

        logModule.clean();
    }
}
