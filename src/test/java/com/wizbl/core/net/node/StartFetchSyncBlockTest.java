package com.wizbl.core.net.node;

import com.wizbl.common.application.Application;
import com.wizbl.common.application.ApplicationFactory;
import com.wizbl.common.application.Brte2ApplicationContext;
import com.wizbl.common.overlay.discover.node.Node;
import com.wizbl.common.overlay.server.Channel;
import com.wizbl.common.overlay.server.ChannelManager;
import com.wizbl.common.overlay.server.SyncPool;
import com.wizbl.common.utils.FileUtil;
import com.wizbl.common.utils.ReflectUtils;
import com.wizbl.common.utils.Sha256Hash;
import com.wizbl.core.capsule.BlockCapsule;
import com.wizbl.core.config.DefaultConfig;
import com.wizbl.core.config.args.Args;
import com.wizbl.core.db.ByteArrayWrapper;
import com.wizbl.core.db.Manager;
import com.wizbl.core.net.message.BlockMessage;
import com.wizbl.core.net.node.override.HandshakeHandlerTest;
import com.wizbl.core.net.node.override.PeerClientTest;
import com.wizbl.core.net.node.override.Brte2ChannelInitializerTest;
import com.wizbl.core.net.peer.PeerConnection;
import com.wizbl.core.services.RpcApiService;
import com.wizbl.core.services.WitnessService;
import com.wizbl.protos.Protocol;
import com.google.common.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

// TEST CLEAR
@Slf4j
public class StartFetchSyncBlockTest {

    private static final String dbPath = "output-nodeImplTest-startFetchSyncBlockTest";
    private static final String dbDirectory = "db_StartFetchSyncBlock_test";
    private static final String indexDirectory = "index_StartFetchSyncBlock_test";
    private static Brte2ApplicationContext context;
    private static PeerClientTest peerClient;
    private static Application appT;
    private static HandshakeHandlerTest handshakeHandlerTest;
    private static boolean go = false;
    private NodeImpl node;
    private RpcApiService rpcApiService;
    private ChannelManager channelManager;
    private SyncPool pool;
    private Node nodeEntity;

    @AfterClass
    public static void destroy() {
        Args.clearParam();
        handshakeHandlerTest.close();
        context.destroy();
        appT.shutdownServices();
        appT.shutdown();
        FileUtil.deleteDir(new File(dbPath));
    }

    private Sha256Hash testBlockBroad() {
        Protocol.Block block = Protocol.Block.getDefaultInstance();
        BlockMessage blockMessage = new BlockMessage(new BlockCapsule(block));
        node.broadcast(blockMessage);
        ConcurrentHashMap<Sha256Hash, Protocol.Inventory.InventoryType> advObjToSpread = ReflectUtils
                .getFieldValue(node, "advObjToSpread");
        Assert.assertEquals(advObjToSpread.get(blockMessage.getMessageId()),
                Protocol.Inventory.InventoryType.BLOCK);
        return blockMessage.getMessageId();
    }

    private BlockMessage removeTheBlock(Sha256Hash blockId) {
        Cache<Sha256Hash, BlockMessage> blockCache = ReflectUtils.getFieldValue(node, "BlockCache");
        BlockMessage blockMessage = blockCache.getIfPresent(blockId);
        if (blockMessage != null) {
            blockCache.invalidate(blockId);
        }
        return blockMessage;
    }

    private void addTheBlock(BlockMessage blockMessag) {
        Cache<Sha256Hash, BlockMessage> blockCache = ReflectUtils.getFieldValue(node, "BlockCache");
        blockCache.put(blockMessag.getMessageId(), blockMessag);
    }

    private Condition testConsumerAdvObjToSpread() {
        Sha256Hash blockId = testBlockBroad();
        //remove the block
        BlockMessage blockMessage = removeTheBlock(blockId);
        ReflectUtils.invokeMethod(node, "consumerAdvObjToSpread");
        Collection<PeerConnection> activePeers = ReflectUtils.invokeMethod(node, "getActivePeer");

        boolean result = true;
        for (PeerConnection peerConnection : activePeers) {
            if (!peerConnection.getAdvObjWeSpread().containsKey(blockId)) {
                result &= false;
            }
        }
        for (PeerConnection peerConnection : activePeers) {
            peerConnection.getAdvObjWeSpread().clear();
        }
        Assert.assertTrue(result);
        return new Condition(blockId);
    }

    @Test
    public void testStartFetchSyncBlock() throws InterruptedException {
        testConsumerAdvObjToSpread();
        Collection<PeerConnection> activePeers = ReflectUtils.invokeMethod(node, "getActivePeer");
        Thread.sleep(5000);
        if (activePeers.size() < 1) {
            return;
        }
        ReflectUtils.setFieldValue(activePeers.iterator().next(), "needSyncFromPeer", true);
        // construct a block
        Protocol.Block block = Protocol.Block.getDefaultInstance();
        BlockMessage blockMessage = new BlockMessage(new BlockCapsule(block));
        // push the block to syncBlockToFetch
        activePeers.iterator().next().getSyncBlockToFetch().push(blockMessage.getBlockId());
        // invoke testing method
        addTheBlock(blockMessage);
        ReflectUtils.invokeMethod(node, "startFetchSyncBlock");
        Cache syncBlockIdWeRequested = ReflectUtils
                .getFieldValue(node, "syncBlockIdWeRequested");
        Assert.assertTrue(syncBlockIdWeRequested.size() == 1);
    }

    @Before
    public void init() {
        nodeEntity = new Node(
                "enode://e437a4836b77ad9d9ffe73ee782ef2614e6d8370fcf62191a6e488276e23717147073a7ce0b444d485fff5a0c34c4577251a7a990cf80d8542e21b95aa8c5e6c@127.0.0.1:17890");

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                logger.info("Full node running.");
                Args.setParam(
                        new String[]{
                                "--output-directory", dbPath,
                                "--storage-db-directory", dbDirectory,
                                "--storage-index-directory", indexDirectory
                        },
                        "config.conf"
                );
                Args cfgArgs = Args.getInstance();
                cfgArgs.setNodeListenPort(17890);
                cfgArgs.setNodeDiscoveryEnable(false);
                cfgArgs.getSeedNode().getIpList().clear();
                cfgArgs.setNeedSyncCheck(false);
                cfgArgs.setNodeExternalIp("127.0.0.1");

                context = new Brte2ApplicationContext(DefaultConfig.class);

                if (cfgArgs.isHelp()) {
                    logger.info("Here is the help message.");
                    return;
                }
                appT = ApplicationFactory.create(context);
                rpcApiService = context.getBean(RpcApiService.class);
                appT.addService(rpcApiService);
                if (cfgArgs.isWitness()) {
                    appT.addService(new WitnessService(appT, context));
                }
//        appT.initServices(cfgArgs);
//        appT.startServices();
//        appT.startup();
                node = context.getBean(NodeImpl.class);
                peerClient = context.getBean(PeerClientTest.class);
                channelManager = context.getBean(ChannelManager.class);
                pool = context.getBean(SyncPool.class);
                Manager dbManager = context.getBean(Manager.class);
                handshakeHandlerTest = context.getBean(HandshakeHandlerTest.class);
                handshakeHandlerTest.setNode(nodeEntity);
                NodeDelegate nodeDelegate = new NodeDelegateImpl(dbManager);
                node.setNodeDelegate(nodeDelegate);
                pool.init(node);
                prepare();
                rpcApiService.blockUntilShutdown();
            }
        });
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        int tryTimes = 0;
        while (tryTimes < 10 && (node == null || peerClient == null
                || channelManager == null || pool == null || !go)) {
            try {
                logger.info("node:{},peerClient:{},channelManager:{},pool:{},{}", node, peerClient,
                        channelManager, pool, go);
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                ++tryTimes;
            }
        }
    }

    private void prepare() {
        try {
            ExecutorService advertiseLoopThread = ReflectUtils.getFieldValue(node, "broadPool");
            advertiseLoopThread.shutdownNow();

            peerClient.prepare(nodeEntity.getHexId());

            ReflectUtils.setFieldValue(node, "isAdvertiseActive", false);
            ReflectUtils.setFieldValue(node, "isFetchActive", false);

            Brte2ChannelInitializerTest brte2ChannelInitializer = ReflectUtils
                    .getFieldValue(peerClient, "brte2ChannelInitializer");
            brte2ChannelInitializer.prepare();
            Channel channel = ReflectUtils.getFieldValue(brte2ChannelInitializer, "channel");
            ReflectUtils.setFieldValue(channel, "handshakeHandler", handshakeHandlerTest);

            new Thread(new Runnable() {
                @Override
                public void run() {
                    peerClient.connect(nodeEntity.getHost(), nodeEntity.getPort(), nodeEntity.getHexId());
                }
            }).start();
            Thread.sleep(1000);
            Map<ByteArrayWrapper, Channel> activePeers = ReflectUtils
                    .getFieldValue(channelManager, "activePeers");
            int tryTimes = 0;
            while (MapUtils.isEmpty(activePeers) && ++tryTimes < 10) {
                Thread.sleep(1000);
            }
            go = true;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private class Condition {

        private final Sha256Hash blockId;

        public Condition(Sha256Hash blockId) {
            this.blockId = blockId;
        }

        public Sha256Hash getBlockId() {
            return blockId;
        }

    }
}
