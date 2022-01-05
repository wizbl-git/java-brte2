package com.wizbl.core.db;

import com.wizbl.common.application.Brte2ApplicationContext;
import com.wizbl.common.utils.ByteArray;
import com.wizbl.common.utils.FileUtil;
import com.wizbl.core.Constant;
import com.wizbl.core.capsule.TransactionInfoCapsule;
import com.wizbl.core.config.DefaultConfig;
import com.wizbl.core.config.args.Args;
import com.wizbl.core.exception.BadItemException;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;

// TEST CLEAR
public class TransactionHistoryTest {

    private static final String dbPath = "output_TransactionHistoryStore_test";
    private static final String dbDirectory = "db_TransactionHistoryStore_test";
    private static final String indexDirectory = "index_TransactionHistoryStore_test";
    private static final Brte2ApplicationContext context;
    private static final byte[] transactionId = TransactionStoreTest.randomBytes(32);
    private static TransactionHistoryStore transactionHistoryStore;

    static {
        Args.setParam(
                new String[]{
                        "--output-directory", dbPath,
                        "--storage-db-directory", dbDirectory,
                        "--storage-index-directory", indexDirectory
                },
                Constant.TEST_CONF
        );
        context = new Brte2ApplicationContext(DefaultConfig.class);
    }

    @AfterClass
    public static void destroy() {
        Args.clearParam();
        context.destroy();
        FileUtil.deleteDir(new File(dbPath));
    }

    @BeforeClass
    public static void init() {
        transactionHistoryStore = context.getBean(TransactionHistoryStore.class);
        TransactionInfoCapsule transactionInfoCapsule = new TransactionInfoCapsule();

        transactionInfoCapsule.setId(transactionId);
        transactionInfoCapsule.setFee(1000L);
        transactionInfoCapsule.setBlockNumber(100L);
        transactionInfoCapsule.setBlockTimeStamp(200L);
        transactionHistoryStore.put(transactionId, transactionInfoCapsule);
    }

    @Test
    public void get() throws BadItemException {
        //test get and has Method
        TransactionInfoCapsule resultCapsule = transactionHistoryStore.get(transactionId);
        Assert.assertEquals(1000L, resultCapsule.getFee());
        Assert.assertEquals(100L, resultCapsule.getBlockNumber());
        Assert.assertEquals(200L, resultCapsule.getBlockTimeStamp());
        Assert.assertEquals(ByteArray.toHexString(transactionId),
                ByteArray.toHexString(resultCapsule.getId()));
    }
}