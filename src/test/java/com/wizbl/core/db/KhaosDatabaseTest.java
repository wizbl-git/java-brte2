package com.wizbl.core.db;

import com.wizbl.common.application.Brte2ApplicationContext;
import com.wizbl.common.utils.ByteArray;
import com.wizbl.common.utils.FileUtil;
import com.wizbl.core.Constant;
import com.wizbl.core.capsule.BlockCapsule;
import com.wizbl.core.config.DefaultConfig;
import com.wizbl.core.config.args.Args;
import com.wizbl.core.exception.BadNumberBlockException;
import com.wizbl.core.exception.UnLinkedBlockException;
import com.wizbl.protos.Protocol.Block;
import com.wizbl.protos.Protocol.BlockHeader;
import com.wizbl.protos.Protocol.BlockHeader.raw;
import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;

// TEST CLEAR
@Slf4j
public class KhaosDatabaseTest {

    private static final String dbPath = "output-khaosDatabase-test";
    private static final Brte2ApplicationContext context;
    private static KhaosDatabase khaosDatabase;

    static {
        Args.setParam(new String[]{"--output-directory", dbPath},
                Constant.TEST_CONF);
        context = new Brte2ApplicationContext(DefaultConfig.class);
    }

    @BeforeClass
    public static void init() {
        Args.setParam(new String[]{"-d", dbPath}, Constant.TEST_CONF);
        khaosDatabase = context.getBean(KhaosDatabase.class);
    }

    @AfterClass
    public static void destroy() {
        Args.clearParam();
        context.destroy();
        FileUtil.deleteDir(new File(dbPath));
    }

    @Test
    public void testStartBlock() {
        BlockCapsule blockCapsule = new BlockCapsule(Block.newBuilder().setBlockHeader(
                BlockHeader.newBuilder().setRawData(raw.newBuilder().setParentHash(ByteString.copyFrom(
                        ByteArray
                                .fromHexString("0304f784e4e7bae517bcab94c3e0c9214fb4ac7ff9d7d5a937d1f40031f87b81")))
                )).build());
        khaosDatabase.start(blockCapsule);

        Assert.assertEquals(blockCapsule, khaosDatabase.getBlock(blockCapsule.getBlockId()));
    }

    @Test
    public void testPushGetBlock() {
        BlockCapsule blockCapsule = new BlockCapsule(Block.newBuilder().setBlockHeader(
                BlockHeader.newBuilder().setRawData(raw.newBuilder().setParentHash(ByteString.copyFrom(
                        ByteArray
                                .fromHexString("0304f784e4e7bae517bcab94c3e0c9214fb4ac7ff9d7d5a937d1f40031f87b81")))
                )).build());
        BlockCapsule blockCapsule2 = new BlockCapsule(Block.newBuilder().setBlockHeader(
                BlockHeader.newBuilder().setRawData(raw.newBuilder().setParentHash(ByteString.copyFrom(
                        ByteArray
                                .fromHexString("9938a342238077182498b464ac029222ae169360e540d1fd6aee7c2ae9575a06")))
                )).build());
        khaosDatabase.start(blockCapsule);
        try {
            khaosDatabase.push(blockCapsule2);
        } catch (UnLinkedBlockException | BadNumberBlockException e) {

        }

        Assert.assertEquals(blockCapsule2, khaosDatabase.getBlock(blockCapsule2.getBlockId()));
        Assert.assertTrue("contain is error", khaosDatabase.containBlock(blockCapsule2.getBlockId()));

        khaosDatabase.removeBlk(blockCapsule2.getBlockId());

        Assert.assertNull("removeBlk is error", khaosDatabase.getBlock(blockCapsule2.getBlockId()));
    }


    @Test
    public void checkWeakReference() throws UnLinkedBlockException, BadNumberBlockException {
        BlockCapsule blockCapsule = new BlockCapsule(Block.newBuilder().setBlockHeader(
                BlockHeader.newBuilder().setRawData(raw.newBuilder().setParentHash(ByteString.copyFrom(
                                ByteArray
                                        .fromHexString("0304f784e4e7bae517bcab94c3e0c9214fb4ac7ff9d7d5a937d1f40031f87b82")))
                        .setNumber(0)
                )).build());
        BlockCapsule blockCapsule2 = new BlockCapsule(Block.newBuilder().setBlockHeader(
                BlockHeader.newBuilder().setRawData(raw.newBuilder().setParentHash(ByteString.copyFrom(
                        blockCapsule.getBlockId().getBytes())).setNumber(1))).build());
        Assert.assertEquals(blockCapsule.getBlockId(), blockCapsule2.getParentHash());

        khaosDatabase.start(blockCapsule);
        khaosDatabase.push(blockCapsule2);

        khaosDatabase.removeBlk(blockCapsule.getBlockId());
        logger.info("*** " + khaosDatabase.getBlock(blockCapsule.getBlockId()));
        Object object = new Object();
        Reference<Object> objectReference = new WeakReference<>(object);
        blockCapsule = null;
        object = null;
        System.gc();
        logger.info("***** object ref:" + objectReference.get());
        Assert.assertNull(objectReference.get());
        Assert.assertNull(khaosDatabase.getParentBlock(blockCapsule2.getBlockId()));
    }
}