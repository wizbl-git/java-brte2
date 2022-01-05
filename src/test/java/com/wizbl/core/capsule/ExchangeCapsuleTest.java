package com.wizbl.core.capsule;

import com.wizbl.common.application.Brte2ApplicationContext;
import com.wizbl.common.utils.FileUtil;
import com.wizbl.core.Constant;
import com.wizbl.core.Wallet;
import com.wizbl.core.config.DefaultConfig;
import com.wizbl.core.config.args.Args;
import com.wizbl.core.db.Manager;
import com.wizbl.core.db.StorageMarket;
import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

import java.io.File;

// TEST FAILED ==> 기능에 대한 이해가 되지 않은 상태임. 추후 확인히 필요함.
@Slf4j
public class ExchangeCapsuleTest {

    private static final String dbPath = "output_exchange_capsule_test_test";
    private static final Brte2ApplicationContext context;
    private static final String OWNER_ADDRESS;
    private static final String OWNER_ADDRESS_INVALID = "aaaa";
    private static final String OWNER_ACCOUNT_INVALID;
    private static final long initBalance = 10_000_000_000_000_000L;
    private static Manager dbManager;
    private static StorageMarket storageMarket;

    static {
        Args.setParam(new String[]{"--output-directory", dbPath}, Constant.TEST_CONF);
        context = new Brte2ApplicationContext(DefaultConfig.class);
        OWNER_ADDRESS = Wallet.getAddressPreFixString() + "548794500882809695a8a687866e76d4271a1abc";
        OWNER_ACCOUNT_INVALID =
                Wallet.getAddressPreFixString() + "548794500882809695a8a687866e76d4271a3456";
    }

    /**
     * Init data.
     */
    @BeforeClass
    public static void init() {
        dbManager = context.getBean(Manager.class);
        storageMarket = new StorageMarket(dbManager);
        //    Args.setParam(new String[]{"--output-directory", dbPath},
        //        "config-junit.conf");
        //    dbManager = new Manager();
        //    dbManager.init();
    }

    /**
     * Release resources.
     */
    @AfterClass
    public static void destroy() {
        Args.clearParam();
        context.destroy();
        if (FileUtil.deleteDir(new File(dbPath))) {
            logger.info("Release resources successful.");
        } else {
            logger.info("Release resources failure.");
        }
    }

    /**
     * create temp Capsule test need.
     */
    @Before
    public void createExchangeCapsule() {
        dbManager.getDynamicPropertiesStore().saveLatestBlockHeaderTimestamp(0);

        long now = dbManager.getHeadBlockTimeStamp();
        ExchangeCapsule exchangeCapsulee =
                new ExchangeCapsule(
                        ByteString.copyFromUtf8("owner"),
                        1,
                        now,
                        "abc".getBytes(),
                        "def".getBytes());

        dbManager.getExchangeStore().put(exchangeCapsulee.createDbKey(), exchangeCapsulee);

    }

//    @Test
//    public void testExchange() {
//        long sellBalance = 100000000L;
//        long buyBalance = 100000000L;
//
//        byte[] key = ByteArray.fromLong(1);
//
//        ExchangeCapsule exchangeCapsule;
//        try {
//            exchangeCapsule = dbManager.getExchangeStore().get(key);
//            exchangeCapsule.setBalance(sellBalance, buyBalance);
//
//            long sellQuant = 100_000_000L;
//            byte[] sellID = "abc".getBytes();
//
//            long result = exchangeCapsule.transaction(sellID, sellQuant);
//            Assert.assertEquals(990_099L, result);
//            sellBalance += sellQuant;
//            Assert.assertEquals(sellBalance, exchangeCapsule.getFirstTokenBalance());
//            buyBalance -= result;
//            Assert.assertEquals(buyBalance, exchangeCapsule.getSecondTokenBalance());
//
//            sellQuant = 9_000_000L;
//            long result2 = exchangeCapsule.transaction(sellID, sellQuant);
//            Assert.assertEquals(9090909L, result + result2);
//            sellBalance += sellQuant;
//            Assert.assertEquals(sellBalance, exchangeCapsule.getFirstTokenBalance());
//            buyBalance -= result2;
//            Assert.assertEquals(buyBalance, exchangeCapsule.getSecondTokenBalance());
//
//        } catch (ItemNotFoundException e) {
//            Assert.fail();
//        }
//
//    }


}
