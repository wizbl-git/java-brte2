package com.wizbl.core.db;

import com.wizbl.common.application.Brte2ApplicationContext;
import com.wizbl.common.crypto.ECKey;
import com.wizbl.common.utils.ByteArray;
import com.wizbl.common.utils.FileUtil;
import com.wizbl.common.utils.Sha256Hash;
import com.wizbl.core.Constant;
import com.wizbl.core.Wallet;
import com.wizbl.core.capsule.AccountCapsule;
import com.wizbl.core.capsule.BlockCapsule;
import com.wizbl.core.capsule.TransactionCapsule;
import com.wizbl.core.config.DefaultConfig;
import com.wizbl.core.config.args.Args;
import com.wizbl.core.exception.BadItemException;
import com.wizbl.core.exception.ItemNotFoundException;
import com.wizbl.protos.Contract.AccountCreateContract;
import com.wizbl.protos.Contract.TransferContract;
import com.wizbl.protos.Protocol.AccountType;
import com.wizbl.protos.Protocol.Transaction.Contract.ContractType;
import com.google.protobuf.ByteString;
import org.junit.*;

import java.io.File;
import java.util.Random;

// TEST CLEAR
@Ignore
public class TransactionStoreTest {

    private static final String dbPath = "output_TransactionStore_test";
    private static final String dbDirectory = "db_TransactionStore_test";
    private static final String indexDirectory = "index_TransactionStore_test";
    private static final Brte2ApplicationContext context;
    private static final byte[] key1 = TransactionStoreTest.randomBytes(21);
    private static final byte[] key2 = TransactionStoreTest.randomBytes(21);
    private static final String URL = "https://acorninc.net";
    private static final String ACCOUNT_NAME = "ownerF";
    private static final String OWNER_ADDRESS =
            Wallet.getAddressPreFixString() + "abd4b9367799eaa3197fecb144eb71de1e049abc";
    private static final String TO_ADDRESS =
            Wallet.getAddressPreFixString() + "abd4b9367799eaa3197fecb144eb71de1e049abc";
    private static final long AMOUNT = 100;
    private static final String WITNESS_ADDRESS =
            Wallet.getAddressPreFixString() + "548794500882809695a8a687866e76d4271a1abc";
    private static TransactionStore transactionStore;
    private static Manager dbManager;

    static {
        Args.setParam(
                new String[]{
                        "--output-directory", dbPath,
                        "--storage-db-directory", dbDirectory,
                        "--storage-index-directory", indexDirectory,
                        "-w"
                },
                Constant.TEST_CONF
        );
        context = new Brte2ApplicationContext(DefaultConfig.class);
    }

    /**
     * Init data.
     */
    @BeforeClass
    public static void init() {
        dbManager = context.getBean(Manager.class);
        transactionStore = dbManager.getTransactionStore();

    }

    @AfterClass
    public static void destroy() {
        Args.clearParam();
        context.destroy();
        FileUtil.deleteDir(new File(dbPath));
    }

    public static byte[] randomBytes(int length) {
        // generate the random number
        byte[] result = new byte[length];
        new Random().nextBytes(result);
//    result[0] = Wallet.getAddressPreFixByte();
        return result;
    }

    /**
     * get AccountCreateContract.
     */
    private AccountCreateContract getContract(String name, String address) {
        return AccountCreateContract.newBuilder()
                .setOwnerAddress(ByteString.copyFrom(ByteArray.fromHexString(address)))
                .build();
    }

    /**
     * get TransferContract.
     */
    private TransferContract getContract(long count, String owneraddress, String toaddress) {
        return TransferContract.newBuilder()
                .setOwnerAddress(ByteString.copyFrom(ByteArray.fromHexString(owneraddress)))
                .setToAddress(ByteString.copyFrom(ByteArray.fromHexString(toaddress)))
                .setAmount(count)
                .build();
    }

    @Test
    public void GetTransactionTest() throws BadItemException, ItemNotFoundException {
        final BlockStore blockStore = dbManager.getBlockStore();
        final TransactionStore trxStore = dbManager.getTransactionStore();
        String key = "f31db24bfbd1a2ef19beddca0a0fa37632eded9ac666a05d3bd925f01dde1f62";

        BlockCapsule blockCapsule =
                new BlockCapsule(
                        1,
                        Sha256Hash.wrap(dbManager.getGenesisBlockId().getByteString()),
                        1,
                        ByteString.copyFrom(
                                ECKey.fromPrivate(
                                        ByteArray.fromHexString(key)).getAddress()));

        // save in database with block number
        TransferContract tc =
                TransferContract.newBuilder()
                        .setAmount(10)
                        .setOwnerAddress(ByteString.copyFromUtf8("aaa"))
                        .setToAddress(ByteString.copyFromUtf8("bbb"))
                        .build();
        TransactionCapsule trx = new TransactionCapsule(tc, ContractType.TransferContract);
        blockCapsule.addTransaction(trx);
        trx.setBlockNum(blockCapsule.getNum());
        blockStore.put(blockCapsule.getBlockId().getBytes(), blockCapsule);
        trxStore.put(trx.getTransactionId().getBytes(), trx);
        Assert.assertEquals("Get transaction is error",
                trxStore.get(trx.getTransactionId().getBytes()).getInstance(), trx.getInstance());

        // no found in transaction store database
        tc =
                TransferContract.newBuilder()
                        .setAmount(1000)
                        .setOwnerAddress(ByteString.copyFromUtf8("aaa"))
                        .setToAddress(ByteString.copyFromUtf8("bbb"))
                        .build();
        trx = new TransactionCapsule(tc, ContractType.TransferContract);
        Assert.assertNull(trxStore.get(trx.getTransactionId().getBytes()));

        // no block number, directly save in database
        tc =
                TransferContract.newBuilder()
                        .setAmount(10000)
                        .setOwnerAddress(ByteString.copyFromUtf8("aaa"))
                        .setToAddress(ByteString.copyFromUtf8("bbb"))
                        .build();
        trx = new TransactionCapsule(tc, ContractType.TransferContract);
        trxStore.put(trx.getTransactionId().getBytes(), trx);
        Assert.assertEquals("Get transaction is error",
                trxStore.get(trx.getTransactionId().getBytes()).getInstance(), trx.getInstance());
    }

    /**
     * put and get CreateAccountTransaction.
     */
    @Test
    public void CreateAccountTransactionStoreTest() throws BadItemException {
        AccountCreateContract accountCreateContract = getContract(ACCOUNT_NAME,
                OWNER_ADDRESS);
        TransactionCapsule ret = new TransactionCapsule(accountCreateContract,
                dbManager.getAccountStore());
        transactionStore.put(key1, ret);
        Assert.assertEquals("Store CreateAccountTransaction is error",
                transactionStore.get(key1).getInstance(),
                ret.getInstance());
        Assert.assertTrue(transactionStore.has(key1));
    }

    /**
     * put and get TransferTransaction.
     */
    @Test
    public void TransferTransactionStorenTest() throws BadItemException {
        AccountCapsule ownerCapsule =
                new AccountCapsule(
                        ByteString.copyFromUtf8(ACCOUNT_NAME),
                        ByteString.copyFrom(ByteArray.fromHexString(OWNER_ADDRESS)),
                        AccountType.AssetIssue,
                        1000000L
                );
        dbManager.getAccountStore().put(ownerCapsule.getAddress().toByteArray(), ownerCapsule);
        TransferContract transferContract = getContract(AMOUNT, OWNER_ADDRESS, TO_ADDRESS);
        TransactionCapsule transactionCapsule = new TransactionCapsule(transferContract,
                dbManager.getAccountStore());
        transactionStore.put(key1, transactionCapsule);
        Assert.assertEquals("Store TransferTransaction is error",
                transactionStore.get(key1).getInstance(),
                transactionCapsule.getInstance());
    }

    /**
     * put and get VoteWitnessTransaction.
     */

    /**
     * put value is null and get it.
     */
    @Test
    public void TransactionValueNullTest() throws BadItemException {
        TransactionCapsule transactionCapsule = null;
        transactionStore.put(key2, transactionCapsule);
        Assert.assertNull("put value is null", transactionStore.get(key2));

    }

    /**
     * put key is null and get it.
     */
    @Test
    public void TransactionKeyNullTest() throws BadItemException {
        AccountCreateContract accountCreateContract = getContract(ACCOUNT_NAME,
                OWNER_ADDRESS);
        TransactionCapsule ret = new TransactionCapsule(accountCreateContract,
                dbManager.getAccountStore());
        byte[] key = null;
        transactionStore.put(key, ret);
        try {
            transactionStore.get(key);
        } catch (RuntimeException e) {
            Assert.assertEquals("The key argument cannot be null", e.getMessage());
        }
    }
}
