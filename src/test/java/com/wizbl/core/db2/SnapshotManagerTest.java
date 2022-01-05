package com.wizbl.core.db2;

import com.wizbl.common.application.Application;
import com.wizbl.common.application.ApplicationFactory;
import com.wizbl.common.application.Brte2ApplicationContext;
import com.wizbl.common.storage.leveldb.LevelDbDataSourceImpl;
import com.wizbl.common.utils.FileUtil;
import com.wizbl.core.Constant;
import com.wizbl.core.config.DefaultConfig;
import com.wizbl.core.config.args.Args;
import com.wizbl.core.db2.RevokingDbWithCacheNewValueTest.TestRevokingBrte2Store;
import com.wizbl.core.db2.RevokingDbWithCacheNewValueTest.TestSnapshotManager;
import com.wizbl.core.db2.SnapshotRootTest.ProtoCapsuleTest;
import com.wizbl.core.db2.core.ISession;
import com.wizbl.core.db2.core.SnapshotManager;
import com.wizbl.core.exception.BadItemException;
import com.wizbl.core.exception.ItemNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

// TEST CLEAR
@Slf4j
public class SnapshotManagerTest {

    private SnapshotManager revokingDatabase;
    private Brte2ApplicationContext context;
    private Application appT;
    private TestRevokingBrte2Store brte2Database;

    @Before
    public void init() {
        Args.setParam(new String[]{"-d", "output_revokingStore_test"},
                Constant.TEST_CONF);
        context = new Brte2ApplicationContext(DefaultConfig.class);
        appT = ApplicationFactory.create(context);
        revokingDatabase = new TestSnapshotManager();
        revokingDatabase.enable();
        brte2Database = new TestRevokingBrte2Store("testSnapshotManager-test");
        revokingDatabase.add(brte2Database.getRevokingDB());
        LevelDbDataSourceImpl tmpLevelDbDataSource =
                new LevelDbDataSourceImpl(Args.getInstance().getOutputDirectoryByDbName("testSnapshotManager-tmp"), "testSnapshotManagerTmp");
        tmpLevelDbDataSource.initDB();
        revokingDatabase.setTmpLevelDbDataSource(tmpLevelDbDataSource);
    }

    @After
    public void removeDb() {
        Args.clearParam();
        appT.shutdownServices();
        appT.shutdown();
        context.destroy();
        brte2Database.close();
        FileUtil.deleteDir(new File("output_revokingStore_test"));
        revokingDatabase.getTmpLevelDbDataSource().closeDB();
        brte2Database.close();
    }

    @Test
    public synchronized void testRefresh()
            throws BadItemException, ItemNotFoundException {
        while (revokingDatabase.size() != 0) {
            revokingDatabase.pop();
        }

        revokingDatabase.setMaxFlushCount(0);
        revokingDatabase.setUnChecked(false);
        revokingDatabase.setMaxSize(5);
        ProtoCapsuleTest protoCapsule = new ProtoCapsuleTest("refresh".getBytes());
        for (int i = 1; i < 11; i++) {
            ProtoCapsuleTest testProtoCapsule = new ProtoCapsuleTest(("refresh" + i).getBytes());
            try (ISession tmpSession = revokingDatabase.buildSession()) {
                brte2Database.put(protoCapsule.getData(), testProtoCapsule);
                tmpSession.commit();
            }
        }

        revokingDatabase.flush();
        Assert.assertEquals(new ProtoCapsuleTest("refresh10".getBytes()),
                brte2Database.get(protoCapsule.getData()));
    }

    @Test
    public synchronized void testClose() {
        while (revokingDatabase.size() != 0) {
            revokingDatabase.pop();
        }

        revokingDatabase.setMaxFlushCount(0);
        revokingDatabase.setUnChecked(false);
        revokingDatabase.setMaxSize(5);
        ProtoCapsuleTest protoCapsule = new ProtoCapsuleTest("close".getBytes());
        for (int i = 1; i < 11; i++) {
            ProtoCapsuleTest testProtoCapsule = new ProtoCapsuleTest(("close" + i).getBytes());
            try (ISession isession = revokingDatabase.buildSession()) {
                brte2Database.put(protoCapsule.getData(), testProtoCapsule);
            }
        }
        Assert.assertEquals(null,
                brte2Database.get(protoCapsule.getData()));

    }
}
