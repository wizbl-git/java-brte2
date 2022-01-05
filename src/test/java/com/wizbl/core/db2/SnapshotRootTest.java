package com.wizbl.core.db2;

import com.wizbl.common.application.Application;
import com.wizbl.common.application.ApplicationFactory;
import com.wizbl.common.application.Brte2ApplicationContext;
import com.wizbl.common.utils.FileUtil;
import com.wizbl.common.utils.SessionOptional;
import com.wizbl.core.Constant;
import com.wizbl.core.capsule.ProtoCapsule;
import com.wizbl.core.config.DefaultConfig;
import com.wizbl.core.config.args.Args;
import com.wizbl.core.db2.RevokingDbWithCacheNewValueTest.TestRevokingBrte2Store;
import com.wizbl.core.db2.RevokingDbWithCacheNewValueTest.TestSnapshotManager;
import com.wizbl.core.db2.core.ISession;
import com.wizbl.core.db2.core.Snapshot;
import com.wizbl.core.db2.core.SnapshotManager;
import com.wizbl.core.db2.core.SnapshotRoot;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// TEST CLEAR
public class SnapshotRootTest {

  private TestRevokingBrte2Store brte2Database;
  private Brte2ApplicationContext context;
  private Application appT;
  private SnapshotManager revokingDatabase;

  @Before
  public void init() {
    Args.setParam(new String[]{"-d", "output_revokingStore_test"}, Constant.TEST_CONF);
    context = new Brte2ApplicationContext(DefaultConfig.class);
    appT = ApplicationFactory.create(context);
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
  public synchronized void testRemove() {
    ProtoCapsuleTest testProtoCapsule = new ProtoCapsuleTest("test".getBytes());
    brte2Database = new TestRevokingBrte2Store("testSnapshotRoot-testRemove");
    brte2Database.put("test".getBytes(), testProtoCapsule);
    Assert.assertEquals(testProtoCapsule, brte2Database.get("test".getBytes()));

    brte2Database.delete("test".getBytes());
    Assert.assertEquals(null, brte2Database.get("test".getBytes()));
    brte2Database.close();
  }

  @Test
  public synchronized void testMerge() {
    brte2Database = new TestRevokingBrte2Store("testSnapshotRoot-testMerge");
    revokingDatabase = new TestSnapshotManager();
    revokingDatabase.enable();
    revokingDatabase.add(brte2Database.getRevokingDB());

    SessionOptional dialog = SessionOptional.instance().setValue(revokingDatabase.buildSession());
    ProtoCapsuleTest testProtoCapsule = new ProtoCapsuleTest("merge".getBytes());
    brte2Database.put(testProtoCapsule.getData(), testProtoCapsule);
    revokingDatabase.getDbs().forEach(db -> db.getHead().getRoot().merge(db.getHead()));
    dialog.reset();
    Assert.assertEquals(brte2Database.get(testProtoCapsule.getData()), testProtoCapsule);

    brte2Database.close();
  }

  @Test
  public synchronized void testMergeList() {
    brte2Database = new TestRevokingBrte2Store("testSnapshotRoot-testMergeList");
    revokingDatabase = new TestSnapshotManager();
    revokingDatabase.enable();
    revokingDatabase.add(brte2Database.getRevokingDB());

    SessionOptional.instance().setValue(revokingDatabase.buildSession());
    ProtoCapsuleTest testProtoCapsule = new ProtoCapsuleTest("test".getBytes());
    brte2Database.put("merge".getBytes(), testProtoCapsule);
    for (int i = 1; i < 11; i++) {
      ProtoCapsuleTest tmpProtoCapsule = new ProtoCapsuleTest(("mergeList" + i).getBytes());
      try (ISession tmpSession = revokingDatabase.buildSession()) {
        brte2Database.put(tmpProtoCapsule.getData(), tmpProtoCapsule);
        tmpSession.commit();
      }
    }
    revokingDatabase.getDbs().forEach(db -> {
      List<Snapshot> snapshots = new ArrayList<>();
      SnapshotRoot root = (SnapshotRoot) db.getHead().getRoot();
      Snapshot next = root;
      for (int i = 0; i < 11; ++i) {
        next = next.getNext();
        snapshots.add(next);
      }
      root.merge(snapshots);
      root.resetSolidity();

      for (int i = 1; i < 11; i++) {
        ProtoCapsuleTest tmpProtoCapsule = new ProtoCapsuleTest(("mergeList" + i).getBytes());
        Assert.assertEquals(tmpProtoCapsule, brte2Database.get(tmpProtoCapsule.getData()));
      }

    });
    revokingDatabase.updateSolidity(10);
    brte2Database.close();
  }

  @NoArgsConstructor
  @AllArgsConstructor
  @EqualsAndHashCode
  public static class ProtoCapsuleTest implements ProtoCapsule<Object> {
    private byte[] value;

    @Override
    public byte[] getData() {
      return value;
    }

    @Override
    public Object getInstance() {
      return value;
    }

    @Override
    public String toString() {
      return "ProtoCapsuleTest{"
              + "value=" + Arrays.toString(value)
              + ", string=" + (value == null ? "" : new String(value))
              + '}';
    }
  }
}
