package com.wizbl.core.net.node;

import com.wizbl.common.application.Application;
import com.wizbl.common.application.ApplicationFactory;
import com.wizbl.common.application.Brte2ApplicationContext;
import com.wizbl.common.crypto.ECKey;
import com.wizbl.common.overlay.server.SyncPool;
import com.wizbl.common.utils.ByteArray;
import com.wizbl.common.utils.FileUtil;
import com.wizbl.common.utils.Sha256Hash;
import com.wizbl.core.Constant;
import com.wizbl.core.capsule.BlockCapsule;
import com.wizbl.core.capsule.BlockCapsule.BlockId;
import com.wizbl.core.capsule.utils.BlockUtil;
import com.wizbl.core.config.DefaultConfig;
import com.wizbl.core.config.Parameter.NetConstants;
import com.wizbl.core.config.args.Args;
import com.wizbl.core.db.Manager;
import com.wizbl.core.net.message.BlockMessage;
import com.wizbl.core.net.peer.PeerConnection;
import com.wizbl.protos.Protocol.Block;
import com.wizbl.protos.Protocol.BlockHeader;
import com.wizbl.protos.Protocol.Inventory.InventoryType;
import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

// TEST CLEAR
@Slf4j
public class NodeImplTest {

    private static final Brte2ApplicationContext context;

    private static final Application appT;
    private static final String dbPath = "output_nodeimpl_test";
    private static NodeImpl nodeImpl;
    private static Manager dbManager;
    private static NodeDelegateImpl nodeDelegate;

    static {
        Args.setParam(new String[]{"-d", dbPath}, Constant.TEST_CONF);
        context = new Brte2ApplicationContext(DefaultConfig.class);
        Args.getInstance().setSolidityNode(true);
        appT = ApplicationFactory.create(context);
    }

    @BeforeClass
    public static void init() {
        nodeImpl = context.getBean(NodeImpl.class);
        dbManager = context.getBean(Manager.class);
        nodeDelegate = new NodeDelegateImpl(dbManager);
        nodeImpl.setNodeDelegate(nodeDelegate);
    }

    @AfterClass
    public static void destroy() {
        Args.clearParam();
        context.destroy();
        appT.shutdownServices();
        appT.shutdown();
        FileUtil.deleteDir(new File(dbPath));
    }

    @Test
    public void testSyncBlockMessage() throws Exception {
        PeerConnection peer = new PeerConnection();
        BlockCapsule genesisBlockCapsule = BlockUtil.newGenesisBlockCapsule();

        ByteString witnessAddress = ByteString.copyFrom(
                ECKey.fromPrivate(
                                ByteArray.fromHexString(
                                        Args.getInstance().getLocalWitnesses().getPrivateKey()))
                        .getAddress());
        BlockHeader.raw raw = BlockHeader.raw.newBuilder()
                .setTimestamp(System.currentTimeMillis())
                .setParentHash(genesisBlockCapsule.getParentHash().getByteString())
                .setNumber(genesisBlockCapsule.getNum() + 1)
                .setWitnessAddress(witnessAddress)
                .setWitnessId(1).build();
        BlockHeader blockHeader = BlockHeader.newBuilder()
                .setRawData(raw)
                .build();

        Block block = Block.newBuilder().setBlockHeader(blockHeader).build();

        BlockCapsule blockCapsule = new BlockCapsule(block);
        blockCapsule.sign(
                ByteArray.fromHexString(Args.getInstance().getLocalWitnesses().getPrivateKey()));
        blockCapsule.setMerkleRoot();
        BlockMessage blockMessage = new BlockMessage(blockCapsule);
        peer.getSyncBlockRequested().put(blockMessage.getBlockId(), System.currentTimeMillis());
        nodeImpl.onMessage(peer, blockMessage);
        Assert.assertEquals(peer.getSyncBlockRequested().size(), 0);
    }

    @Test
    public void testAdvBlockMessage() throws Exception {
        PeerConnection peer = new PeerConnection();
        BlockCapsule genesisBlockCapsule = BlockUtil.newGenesisBlockCapsule();

        ByteString witnessAddress = ByteString.copyFrom(
                ECKey.fromPrivate(
                                ByteArray.fromHexString(
                                        Args.getInstance().getLocalWitnesses().getPrivateKey()))
                        .getAddress());
        BlockHeader.raw raw = BlockHeader.raw.newBuilder()
                .setTimestamp(System.currentTimeMillis())
                .setParentHash(genesisBlockCapsule.getBlockId().getByteString())
                .setNumber(genesisBlockCapsule.getNum() + 1)
                .setWitnessAddress(witnessAddress)
                .setWitnessId(1).build();
        BlockHeader blockHeader = BlockHeader.newBuilder()
                .setRawData(raw)
                .build();

        Block block = Block.newBuilder().setBlockHeader(blockHeader).build();

        BlockCapsule blockCapsule = new BlockCapsule(block);
        blockCapsule.setMerkleRoot();
        blockCapsule.sign(
                ByteArray.fromHexString(Args.getInstance().getLocalWitnesses().getPrivateKey()));
        BlockMessage blockMessage = new BlockMessage(blockCapsule);
        peer.getAdvObjWeRequested().put(new Item(blockMessage.getBlockId(), InventoryType.BLOCK), System.currentTimeMillis());
        nodeImpl.onMessage(peer, blockMessage);
        Assert.assertEquals(peer.getAdvObjWeRequested().size(), 0);
    }

    //  @Test
    public void testDisconnectInactive() {
        // generate test data
        ConcurrentHashMap<Item, Long> advObjWeRequested1 = new ConcurrentHashMap<>();
        ConcurrentHashMap<Item, Long> advObjWeRequested2 = new ConcurrentHashMap<>();
        ConcurrentHashMap<Item, Long> advObjWeRequested3 = new ConcurrentHashMap<>();
        ConcurrentHashMap<BlockId, Long> syncBlockRequested1 = new ConcurrentHashMap<>();
        ConcurrentHashMap<BlockId, Long> syncBlockRequested2 = new ConcurrentHashMap<>();
        ConcurrentHashMap<BlockId, Long> syncBlockRequested3 = new ConcurrentHashMap<>();

        advObjWeRequested1.put(new Item(new Sha256Hash(1, Sha256Hash.ZERO_HASH), InventoryType.TRX),
                System.currentTimeMillis() - NetConstants.ADV_TIME_OUT);
        syncBlockRequested1.put(new BlockId(),
                System.currentTimeMillis());
        advObjWeRequested2.put(new Item(new Sha256Hash(1, Sha256Hash.ZERO_HASH), InventoryType.TRX),
                System.currentTimeMillis());
        syncBlockRequested2.put(new BlockId(),
                System.currentTimeMillis() - NetConstants.SYNC_TIME_OUT);
        advObjWeRequested3.put(new Item(new Sha256Hash(1, Sha256Hash.ZERO_HASH), InventoryType.TRX),
                System.currentTimeMillis());
        syncBlockRequested3.put(new BlockId(),
                System.currentTimeMillis());

        PeerConnection peer1 = new PeerConnection();
        PeerConnection peer2 = new PeerConnection();
        PeerConnection peer3 = new PeerConnection();

        peer1.setAdvObjWeRequested(advObjWeRequested1);
        peer1.setSyncBlockRequested(syncBlockRequested1);
        peer2.setAdvObjWeRequested(advObjWeRequested2);
        peer2.setSyncBlockRequested(syncBlockRequested2);
        peer3.setAdvObjWeRequested(advObjWeRequested3);
        peer3.setSyncBlockRequested(syncBlockRequested3);

        // fetch failed
        SyncPool pool = new SyncPool();
        pool.addActivePeers(peer1);
        nodeImpl.setPool(pool);
        try {
            nodeImpl.disconnectInactive();
            fail("disconnectInactive failed");
        } catch (RuntimeException e) {
            assertTrue("disconnect successfully, reason is fetch failed", true);
        }

        // sync failed
        pool = new SyncPool();
        pool.addActivePeers(peer2);
        nodeImpl.setPool(pool);
        try {
            nodeImpl.disconnectInactive();
            fail("disconnectInactive failed");
        } catch (RuntimeException e) {
            assertTrue("disconnect successfully, reason is sync failed", true);
        }

        // should not disconnect
        pool = new SyncPool();
        pool.addActivePeers(peer3);
        nodeImpl.setPool(pool);
        try {
            nodeImpl.disconnectInactive();
            assertTrue("not disconnect", true);
        } catch (RuntimeException e) {
            fail("should not disconnect!");
        }
    }
}
