package com.wizbl.core.db2;

import com.wizbl.common.application.Application;
import com.wizbl.common.application.ApplicationFactory;
import com.wizbl.common.application.Brte2ApplicationContext;
import com.wizbl.common.utils.FileUtil;
import com.wizbl.common.utils.SessionOptional;
import com.wizbl.core.Constant;
import com.wizbl.core.config.DefaultConfig;
import com.wizbl.core.config.args.Args;
import com.wizbl.core.db.AbstractRevokingStore;
import com.wizbl.core.db.RevokingDatabase;
import com.wizbl.core.db.Brte2StoreWithRevoking;
import com.wizbl.core.db2.SnapshotRootTest.ProtoCapsuleTest;
import com.wizbl.core.db2.core.ISession;
import com.wizbl.core.exception.RevokingStoreIllegalStateException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

// TEST CLEAR
@Slf4j
public class RevokingDbWithCacheOldValueTest {

    private AbstractRevokingStore revokingDatabase;
    private Brte2ApplicationContext context;
    private Application appT;

    @Before
    public void init() {
        Args.setParam(new String[]{"-d", "output_revokingStore_test"}, Constant.TEST_CONF);
        context = new Brte2ApplicationContext(DefaultConfig.class);
        appT = ApplicationFactory.create(context);
        revokingDatabase = new Brte2RevokingBrte2Database();
        revokingDatabase.enable();
    }

    @After
    public void removeDb() {
        Args.clearParam();
        appT.shutdownServices();
        appT.shutdown();
        context.destroy();
        FileUtil.deleteDir(new File("output_revokingStore_test"));
    }

    @Test
    public synchronized void testReset() {
        revokingDatabase.getStack().clear();
        TestRevokingBrte2Store brte2Database = new TestRevokingBrte2Store(
                "testrevokingbrte2store-testReset", revokingDatabase);
        ProtoCapsuleTest testProtoCapsule = new ProtoCapsuleTest(("reset").getBytes());
        try (ISession tmpSession = revokingDatabase.buildSession()) {
            brte2Database.put(testProtoCapsule.getData(), testProtoCapsule);
            tmpSession.commit();
        }
        Assert.assertEquals(true, brte2Database.has(testProtoCapsule.getData()));
        brte2Database.reset();
        Assert.assertEquals(false, brte2Database.has(testProtoCapsule.getData()));
    }

    @Test
    public synchronized void testPop() throws RevokingStoreIllegalStateException {
        revokingDatabase.getStack().clear();
        TestRevokingBrte2Store brte2Database = new TestRevokingBrte2Store(
                "testrevokingbrte2store-testPop", revokingDatabase);

        for (int i = 1; i < 11; i++) {
            ProtoCapsuleTest testProtoCapsule = new ProtoCapsuleTest(("pop" + i).getBytes());
            try (ISession tmpSession = revokingDatabase.buildSession()) {
                brte2Database.put(testProtoCapsule.getData(), testProtoCapsule);
                Assert.assertEquals(1, revokingDatabase.getActiveDialog());
                tmpSession.commit();
                Assert.assertEquals(i, revokingDatabase.getStack().size());
                Assert.assertEquals(0, revokingDatabase.getActiveDialog());
            }
        }

        for (int i = 1; i < 11; i++) {
            revokingDatabase.pop();
            Assert.assertEquals(10 - i, revokingDatabase.getStack().size());
        }

        brte2Database.close();

        Assert.assertEquals(0, revokingDatabase.getStack().size());
    }

    @Test
    public synchronized void testUndo() throws RevokingStoreIllegalStateException {
        revokingDatabase.getStack().clear();
        TestRevokingBrte2Store brte2Database = new TestRevokingBrte2Store(
                "testrevokingbrte2store-testUndo", revokingDatabase);

        SessionOptional dialog = SessionOptional.instance().setValue(revokingDatabase.buildSession());
        for (int i = 0; i < 10; i++) {
            ProtoCapsuleTest testProtoCapsule = new ProtoCapsuleTest(("undo" + i).getBytes());
            try (ISession tmpSession = revokingDatabase.buildSession()) {
                brte2Database.put(testProtoCapsule.getData(), testProtoCapsule);
                Assert.assertEquals(2, revokingDatabase.getStack().size());
                tmpSession.merge();
                Assert.assertEquals(1, revokingDatabase.getStack().size());
            }
        }

        Assert.assertEquals(1, revokingDatabase.getStack().size());

        dialog.reset();

        Assert.assertTrue(revokingDatabase.getStack().isEmpty());
        Assert.assertEquals(0, revokingDatabase.getActiveDialog());

        dialog = SessionOptional.instance().setValue(revokingDatabase.buildSession());
        revokingDatabase.disable();
        ProtoCapsuleTest testProtoCapsule = new ProtoCapsuleTest("del".getBytes());
        brte2Database.put(testProtoCapsule.getData(), testProtoCapsule);
        revokingDatabase.enable();

        try (ISession tmpSession = revokingDatabase.buildSession()) {
            brte2Database.put(testProtoCapsule.getData(), new ProtoCapsuleTest("del2".getBytes()));
            tmpSession.merge();
        }

        try (ISession tmpSession = revokingDatabase.buildSession()) {
            brte2Database.put(testProtoCapsule.getData(), new ProtoCapsuleTest("del22".getBytes()));
            tmpSession.merge();
        }

        try (ISession tmpSession = revokingDatabase.buildSession()) {
            brte2Database.put(testProtoCapsule.getData(), new ProtoCapsuleTest("del222".getBytes()));
            tmpSession.merge();
        }

        try (ISession tmpSession = revokingDatabase.buildSession()) {
            brte2Database.delete(testProtoCapsule.getData());
            tmpSession.merge();
        }

        dialog.reset();

        logger.info("**********testProtoCapsule:" + brte2Database.getUnchecked(testProtoCapsule.getData()));
        Assert.assertArrayEquals("del".getBytes(),
                brte2Database.getUnchecked(testProtoCapsule.getData()).getData());
        Assert.assertEquals(testProtoCapsule, brte2Database.getUnchecked(testProtoCapsule.getData()));

        brte2Database.close();
    }

    @Test
    public synchronized void testGetlatestValues() {
        revokingDatabase.getStack().clear();
        TestRevokingBrte2Store brte2Database = new TestRevokingBrte2Store(
                "testrevokingbrte2store-testGetlatestValues", revokingDatabase);

        for (int i = 0; i < 10; i++) {
            ProtoCapsuleTest testProtoCapsule = new ProtoCapsuleTest(("getLastestValues" + i).getBytes());
            try (ISession tmpSession = revokingDatabase.buildSession()) {
                brte2Database.put(testProtoCapsule.getData(), testProtoCapsule);
                tmpSession.commit();
            }
        }
        Set<ProtoCapsuleTest> result = brte2Database.getRevokingDB().getlatestValues(5).stream()
                .map(ProtoCapsuleTest::new)
                .collect(Collectors.toSet());

        for (int i = 9; i >= 5; i--) {
            Assert.assertEquals(true,
                    result.contains(new ProtoCapsuleTest(("getLastestValues" + i).getBytes())));
        }
        brte2Database.close();
    }

    @Test
    public synchronized void testGetValuesNext() {
        revokingDatabase.getStack().clear();
        TestRevokingBrte2Store brte2Database = new TestRevokingBrte2Store(
                "testrevokingbrte2store-testGetValuesNext", revokingDatabase);

        for (int i = 0; i < 10; i++) {
            ProtoCapsuleTest testProtoCapsule = new ProtoCapsuleTest(("getValuesNext" + i).getBytes());
            try (ISession tmpSession = revokingDatabase.buildSession()) {
                brte2Database.put(testProtoCapsule.getData(), testProtoCapsule);
                tmpSession.commit();
            }
        }
        Set<ProtoCapsuleTest> result =
                brte2Database.getRevokingDB().getValuesNext(
                                new ProtoCapsuleTest("getValuesNext2".getBytes()).getData(), 3)
                        .stream()
                        .map(ProtoCapsuleTest::new)
                        .collect(Collectors.toSet());

        for (int i = 2; i < 5; i++) {
            Assert.assertEquals(true,
                    result.contains(new ProtoCapsuleTest(("getValuesNext" + i).getBytes())));
        }
        brte2Database.close();
    }

    @Test
    public void shutdown() throws RevokingStoreIllegalStateException {
        revokingDatabase.getStack().clear();
        TestRevokingBrte2Store brte2Database = new TestRevokingBrte2Store(
                "testrevokingbrte2store-shutdown", revokingDatabase);

        List<ProtoCapsuleTest> capsules = new ArrayList<>();
        for (int i = 1; i < 11; i++) {
            revokingDatabase.buildSession();
            ProtoCapsuleTest testProtoCapsule = new ProtoCapsuleTest(("test" + i).getBytes());
            capsules.add(testProtoCapsule);
            brte2Database.put(testProtoCapsule.getData(), testProtoCapsule);
            Assert.assertEquals(revokingDatabase.getActiveDialog(), i);
            Assert.assertEquals(revokingDatabase.getStack().size(), i);
        }

        for (ProtoCapsuleTest capsule : capsules) {
            logger.info(new String(capsule.getData()));
            Assert.assertEquals(capsule, brte2Database.getUnchecked(capsule.getData()));
        }

        revokingDatabase.shutdown();

        for (ProtoCapsuleTest capsule : capsules) {
            logger.info(brte2Database.getUnchecked(capsule.getData()).toString());
            Assert.assertEquals(null, brte2Database.getUnchecked(capsule.getData()).getData());
        }

        Assert.assertEquals(0, revokingDatabase.getStack().size());
        brte2Database.close();

    }

    private static class TestRevokingBrte2Store extends Brte2StoreWithRevoking<ProtoCapsuleTest> {

        protected TestRevokingBrte2Store(String dbName, RevokingDatabase revokingDatabase) {
            super(dbName, revokingDatabase);
        }

        @Override
        public ProtoCapsuleTest get(byte[] key) {
            byte[] value = this.revokingDB.getUnchecked(key);
            return ArrayUtils.isEmpty(value) ? null : new ProtoCapsuleTest(value);
        }
    }

    private static class Brte2RevokingBrte2Database extends AbstractRevokingStore {

    }
}
