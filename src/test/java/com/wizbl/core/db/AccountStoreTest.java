package com.wizbl.core.db;

import com.wizbl.common.application.Brte2ApplicationContext;
import com.wizbl.common.utils.ByteArray;
import com.wizbl.common.utils.FileUtil;
import com.wizbl.core.Constant;
import com.wizbl.core.capsule.AccountCapsule;
import com.wizbl.core.config.DefaultConfig;
import com.wizbl.core.config.args.Args;
import com.wizbl.protos.Protocol.AccountType;
import com.google.protobuf.ByteString;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;

// TEST CLEAR
public class AccountStoreTest {

    private static final String dbPath = "output_AccountStore_test";
    private static final String dbDirectory = "db_AccountStore_test";
    private static final String indexDirectory = "index_AccountStore_test";
    private static final Brte2ApplicationContext context;
    private static final byte[] data = TransactionStoreTest.randomBytes(32);
    private static final byte[] address = TransactionStoreTest.randomBytes(32);
    private static final byte[] accountName = TransactionStoreTest.randomBytes(32);
    private static AccountStore accountStore;

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
        accountStore = context.getBean(AccountStore.class);
        AccountCapsule accountCapsule = new AccountCapsule(ByteString.copyFrom(address),
                ByteString.copyFrom(accountName),
                AccountType.forNumber(1));
        accountStore.put(data, accountCapsule);
    }

    @Test
    public void get() {
        //test get and has Method
        Assert
                .assertEquals(ByteArray.toHexString(address), ByteArray
                        .toHexString(accountStore.get(data).getInstance().getAddress().toByteArray()))
        ;
        Assert
                .assertEquals(ByteArray.toHexString(accountName), ByteArray
                        .toHexString(accountStore.get(data).getInstance().getAccountName().toByteArray()))
        ;
        Assert.assertTrue(accountStore.has(data));
    }
}