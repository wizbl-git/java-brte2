package com.wizbl.core;

import com.wizbl.common.application.Brte2ApplicationContext;
import com.wizbl.common.utils.ByteArray;
import com.wizbl.common.utils.FileUtil;
import com.wizbl.core.capsule.AccountCapsule;
import com.wizbl.core.config.DefaultConfig;
import com.wizbl.core.config.Parameter.ChainConstant;
import com.wizbl.core.config.args.Args;
import com.wizbl.core.db.Manager;
import com.wizbl.core.db.StorageMarket;
import com.wizbl.protos.Contract;
import com.wizbl.protos.Protocol.AccountType;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.junit.*;

import java.io.File;

// TEST CLEAR
@Slf4j
public class StorageMarketTest {

  private static final String dbPath = "output_storage_market_test";
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
  public void createAccountCapsule() {
    AccountCapsule ownerCapsule =
            new AccountCapsule(
                    ByteString.copyFromUtf8("owner"),
                    ByteString.copyFrom(ByteArray.fromHexString(OWNER_ADDRESS)),
                    AccountType.Normal,
                    initBalance);
    dbManager.getAccountStore().put(ownerCapsule.getAddress().toByteArray(), ownerCapsule);

    dbManager.getDynamicPropertiesStore().saveTotalStorageReserved(
            128L * 1024 * 1024 * 1024);
    dbManager.getDynamicPropertiesStore().saveTotalStoragePool(100_000_000_000000L);
    dbManager.getDynamicPropertiesStore().saveTotalStorageTax(0);

    dbManager.getDynamicPropertiesStore().saveLatestBlockHeaderTimestamp(0);
  }

  private Any getContract(String ownerAddress, long quant) {
    return Any.pack(
            Contract.BuyStorageContract.newBuilder()
                    .setOwnerAddress(ByteString.copyFrom(ByteArray.fromHexString(ownerAddress)))
                    .setQuant(quant)
                    .build());
  }

  @Test
  public void testBuyStorage() {
    long currentPool = dbManager.getDynamicPropertiesStore().getTotalStoragePool();
    long currentReserved = dbManager.getDynamicPropertiesStore().getTotalStorageReserved();
    Assert.assertEquals(currentPool, 100_000_000_000000L);
    Assert.assertEquals(currentReserved, 128L * 1024 * 1024 * 1024);

    AccountCapsule owner =
            dbManager.getAccountStore().get(ByteArray.fromHexString(OWNER_ADDRESS));

    long quant = 2_000_000_000_000L; // 2 million trx
    storageMarket.buyStorage(owner, quant);

    Assert.assertEquals(owner.getBalance(), initBalance - quant
            - ChainConstant.TRANSFER_FEE);
    Assert.assertEquals(2694881440L, owner.getStorageLimit());
    Assert.assertEquals(currentReserved - 2694881440L,
            dbManager.getDynamicPropertiesStore().getTotalStorageReserved());
    Assert.assertEquals(currentPool + quant,
            dbManager.getDynamicPropertiesStore().getTotalStoragePool());

  }

  @Test
  public void testBuyStorage2() {
    long currentPool = dbManager.getDynamicPropertiesStore().getTotalStoragePool();
    long currentReserved = dbManager.getDynamicPropertiesStore().getTotalStorageReserved();
    Assert.assertEquals(currentPool, 100_000_000_000000L);
    Assert.assertEquals(currentReserved, 128L * 1024 * 1024 * 1024);

    AccountCapsule owner =
            dbManager.getAccountStore().get(ByteArray.fromHexString(OWNER_ADDRESS));

    long quant = 1_000_000_000_000L; // 1 million trx

    storageMarket.buyStorage(owner, quant);

    Assert.assertEquals(owner.getBalance(), initBalance - quant
            - ChainConstant.TRANSFER_FEE);
    Assert.assertEquals(1360781717L, owner.getStorageLimit());
    Assert.assertEquals(currentReserved - 1360781717L,
            dbManager.getDynamicPropertiesStore().getTotalStorageReserved());
    Assert.assertEquals(currentPool + quant,
            dbManager.getDynamicPropertiesStore().getTotalStoragePool());

    storageMarket.buyStorage(owner, quant);

    Assert.assertEquals(owner.getBalance(), initBalance - 2 * quant
            - ChainConstant.TRANSFER_FEE);
    Assert.assertEquals(2694881439L, owner.getStorageLimit());
    Assert.assertEquals(currentReserved - 2694881439L,
            dbManager.getDynamicPropertiesStore().getTotalStorageReserved());
    Assert.assertEquals(currentPool + 2 * quant,
            dbManager.getDynamicPropertiesStore().getTotalStoragePool());

  }


  @Test
  public void testBuyStorageBytes() {
    long currentPool = dbManager.getDynamicPropertiesStore().getTotalStoragePool();
    long currentReserved = dbManager.getDynamicPropertiesStore().getTotalStorageReserved();
    Assert.assertEquals(currentPool, 100_000_000_000000L);
    Assert.assertEquals(currentReserved, 128L * 1024 * 1024 * 1024);

    AccountCapsule owner =
            dbManager.getAccountStore().get(ByteArray.fromHexString(OWNER_ADDRESS));

    long bytes = 2694881440L; // 2 million trx
    storageMarket.buyStorageBytes(owner, bytes);

    Assert.assertEquals(owner.getBalance(), initBalance - 2_000_000_000_000L
            - ChainConstant.TRANSFER_FEE);
    Assert.assertEquals(bytes, owner.getStorageLimit());
    Assert.assertEquals(currentReserved - bytes,
            dbManager.getDynamicPropertiesStore().getTotalStorageReserved());
    Assert.assertEquals(currentPool + 2_000_000_000_000L,
            dbManager.getDynamicPropertiesStore().getTotalStoragePool());

  }

  @Test
  public void testBuyStorageBytes2() {
    long currentPool = dbManager.getDynamicPropertiesStore().getTotalStoragePool();
    long currentReserved = dbManager.getDynamicPropertiesStore().getTotalStorageReserved();
    Assert.assertEquals(currentPool, 100_000_000_000000L);
    Assert.assertEquals(currentReserved, 128L * 1024 * 1024 * 1024);

    AccountCapsule owner =
            dbManager.getAccountStore().get(ByteArray.fromHexString(OWNER_ADDRESS));

    long bytes1 = 1360781717L;

    storageMarket.buyStorageBytes(owner, bytes1);

    Assert.assertEquals(owner.getBalance(), initBalance - 1_000_000_000_000L
            - ChainConstant.TRANSFER_FEE);
    Assert.assertEquals(bytes1, owner.getStorageLimit());
    Assert.assertEquals(currentReserved - bytes1,
            dbManager.getDynamicPropertiesStore().getTotalStorageReserved());
    Assert.assertEquals(currentPool + 1_000_000_000_000L,
            dbManager.getDynamicPropertiesStore().getTotalStoragePool());

    long bytes2 = 1334099723L;
    storageMarket.buyStorageBytes(owner, bytes2);
    Assert.assertEquals(owner.getBalance(), initBalance - 2 * 1_000_000_000_000L
            - ChainConstant.TRANSFER_FEE);
    Assert.assertEquals(bytes1 + bytes2, owner.getStorageLimit());
    Assert.assertEquals(currentReserved - (bytes1 + bytes2),
            dbManager.getDynamicPropertiesStore().getTotalStorageReserved());
    Assert.assertEquals(currentPool + 2 * 1_000_000_000_000L,
            dbManager.getDynamicPropertiesStore().getTotalStoragePool());

  }

  @Test
  public void testSellStorage() {
    long currentPool = dbManager.getDynamicPropertiesStore().getTotalStoragePool();
    long currentReserved = dbManager.getDynamicPropertiesStore().getTotalStorageReserved();
    Assert.assertEquals(currentPool, 100_000_000_000000L);
    Assert.assertEquals(currentReserved, 128L * 1024 * 1024 * 1024);

    AccountCapsule owner =
            dbManager.getAccountStore().get(ByteArray.fromHexString(OWNER_ADDRESS));

    long quant = 2_000_000_000_000L; // 2 million trx
    storageMarket.buyStorage(owner, quant);

    Assert.assertEquals(owner.getBalance(), initBalance - quant
            - ChainConstant.TRANSFER_FEE);
    Assert.assertEquals(2694881440L, owner.getStorageLimit());
    Assert.assertEquals(currentReserved - 2694881440L,
            dbManager.getDynamicPropertiesStore().getTotalStorageReserved());
    Assert.assertEquals(currentPool + quant,
            dbManager.getDynamicPropertiesStore().getTotalStoragePool());

    long bytes = 2694881440L;
    storageMarket.sellStorage(owner, bytes);

    Assert.assertEquals(owner.getBalance(), initBalance);
    Assert.assertEquals(0, owner.getStorageLimit());
    Assert.assertEquals(currentReserved,
            dbManager.getDynamicPropertiesStore().getTotalStorageReserved());
    Assert.assertEquals(100_000_000_000_000L,
            dbManager.getDynamicPropertiesStore().getTotalStoragePool());

  }

  @Test
  public void testSellStorage2() {
    long currentPool = dbManager.getDynamicPropertiesStore().getTotalStoragePool();
    long currentReserved = dbManager.getDynamicPropertiesStore().getTotalStorageReserved();
    Assert.assertEquals(currentPool, 100_000_000_000000L);
    Assert.assertEquals(currentReserved, 128L * 1024 * 1024 * 1024);

    AccountCapsule owner =
            dbManager.getAccountStore().get(ByteArray.fromHexString(OWNER_ADDRESS));

    long quant = 2_000_000_000_000L; // 2 million trx
    storageMarket.buyStorage(owner, quant);

    Assert.assertEquals(owner.getBalance(), initBalance - quant
            - ChainConstant.TRANSFER_FEE);
    Assert.assertEquals(2694881440L, owner.getStorageLimit());
    Assert.assertEquals(currentReserved - 2694881440L,
            dbManager.getDynamicPropertiesStore().getTotalStorageReserved());
    Assert.assertEquals(currentPool + quant,
            dbManager.getDynamicPropertiesStore().getTotalStoragePool());

    long bytes1 = 2694881440L - 1360781717L; // 1 million trx
    long bytes2 = 1360781717L; // 1 million trx

    storageMarket.sellStorage(owner, bytes1);

    Assert.assertEquals(owner.getBalance(), initBalance - 1_000_000_000_000L);
    Assert.assertEquals(1360781717L, owner.getStorageLimit());
    Assert.assertEquals(currentReserved - 1360781717L,
            dbManager.getDynamicPropertiesStore().getTotalStorageReserved());
    Assert.assertEquals(currentPool + 1_000_000_000_000L,
            dbManager.getDynamicPropertiesStore().getTotalStoragePool());

    storageMarket.sellStorage(owner, bytes2);

    Assert.assertEquals(owner.getBalance(), initBalance);
    Assert.assertEquals(0, owner.getStorageLimit());
    Assert.assertEquals(currentReserved,
            dbManager.getDynamicPropertiesStore().getTotalStorageReserved());
    Assert.assertEquals(currentPool,
            dbManager.getDynamicPropertiesStore().getTotalStoragePool());

  }


}
