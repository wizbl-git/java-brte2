package com.wizbl.core.capsule;

import com.wizbl.common.utils.ByteArray;
import com.wizbl.common.utils.FileUtil;
import com.wizbl.common.utils.Sha256Hash;
import com.wizbl.core.Constant;
import com.wizbl.core.Wallet;
import com.wizbl.core.config.args.Args;
import com.wizbl.core.exception.BadItemException;
import com.wizbl.protos.Contract.TransferContract;
import com.wizbl.protos.Protocol.Transaction.Contract.ContractType;
import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;

// TEST CLEAR
@Slf4j
public class BlockCapsuleTest {

    private static final BlockCapsule blockCapsule0 = new BlockCapsule(1,
            Sha256Hash.wrap(ByteString
                    .copyFrom(ByteArray
                            .fromHexString("9938a342238077182498b464ac0292229938a342238077182498b464ac029222"))),
            1234,
            ByteString.copyFrom("1234567".getBytes()));
    private static final String dbPath = "output_bloackcapsule_test";

    @BeforeClass
    public static void init() {
        Args.setParam(new String[]{"-d", dbPath},
                Constant.TEST_CONF);
    }

    @AfterClass
    public static void removeDb() {
        Args.clearParam();
        FileUtil.deleteDir(new File(dbPath));
    }

    @Test
    public void testCalcMerkleRoot() throws Exception {
        blockCapsule0.setMerkleRoot();
        Assert.assertEquals(
                Sha256Hash.wrap(Sha256Hash.ZERO_HASH.getByteString()).toString(),
                blockCapsule0.getMerkleRoot().toString());

        logger.info("Transaction[X] Merkle Root : {}", blockCapsule0.getMerkleRoot().toString());

        TransferContract transferContract1 = TransferContract.newBuilder()
                .setAmount(1L)
                .setOwnerAddress(ByteString.copyFrom("0x0000000000000000000".getBytes()))
                .setToAddress(ByteString.copyFrom(ByteArray.fromHexString(
                        (Wallet.getAddressPreFixString() + "A389132D6639FBDA4FBC8B659264E6B7C90DB086"))))
                .build();

        TransferContract transferContract2 = TransferContract.newBuilder()
                .setAmount(2L)
                .setOwnerAddress(ByteString.copyFrom("0x0000000000000000000".getBytes()))
                .setToAddress(ByteString.copyFrom(ByteArray.fromHexString(
                        (Wallet.getAddressPreFixString() + "ED738B3A0FE390EAA71B768B6D02CDBD18FB207B"))))
                .build();

        blockCapsule0
                .addTransaction(new TransactionCapsule(transferContract1, ContractType.TransferContract));
        blockCapsule0
                .addTransaction(new TransactionCapsule(transferContract2, ContractType.TransferContract));
        blockCapsule0.setMerkleRoot();

        if (Constant.ADD_PRE_FIX_BYTE_TESTNET == Wallet.getAddressPreFixByte()) {
            Assert.assertEquals(
                    "ad9f3c232c4d845f4981cf086590ddc27a4e04f44176c0458eb7061a1247ff3c",
                    blockCapsule0.getMerkleRoot().toString());
        } else {
            Assert.assertEquals(
                    "5bc862243292e6aa1d5e21a60bb6a673e4c2544709f6363d4a2f85ec29bcfe00",
                    blockCapsule0.getMerkleRoot().toString());
        }

        logger.info("Transaction[O] Merkle Root : {}", blockCapsule0.getMerkleRoot().toString());
    }

  /* @Test
  public void testAddTransaction() {
    TransactionCapsule transactionCapsule = new TransactionCapsule("123", 1L);
    blockCapsule0.addTransaction(transactionCapsule);
    Assert.assertArrayEquals(blockCapsule0.getTransactions().get(0).getHash().getBytes(),
        transactionCapsule.getHash().getBytes());
    Assert.assertEquals(transactionCapsule.getInstance().getRawData().getVout(0).getValue(),
        blockCapsule0.getTransactions().get(0).getInstance().getRawData().getVout(0).getValue());
  } */

    @Test
    public void testGetData() {
        blockCapsule0.getData();
        byte[] b = blockCapsule0.getData();
        BlockCapsule blockCapsule1 = null;
        try {
            blockCapsule1 = new BlockCapsule(b);
            Assert.assertEquals(blockCapsule0.getBlockId(), blockCapsule1.getBlockId());
        } catch (BadItemException e) {
            e.printStackTrace();
        }

    }

    @Test
    public void testValidate() {

    }

    @Test
    public void testGetInsHash() {
        Assert.assertEquals(1,
                blockCapsule0.getInstance().getBlockHeader().getRawData().getNumber());
        Assert.assertEquals(blockCapsule0.getParentHash(),
                Sha256Hash.wrap(blockCapsule0.getParentHashStr()));
    }

    @Test
    public void testGetTimeStamp() {
        Assert.assertEquals(1234L, blockCapsule0.getTimeStamp());
    }

}