package com.wizbl.core.net.node;

import com.wizbl.common.application.Application;
import com.wizbl.common.application.ApplicationFactory;
import com.wizbl.common.application.Brte2ApplicationContext;
import com.wizbl.common.overlay.discover.node.Node;
import com.wizbl.common.overlay.message.Message;
import com.wizbl.common.overlay.server.Channel;
import com.wizbl.common.overlay.server.ChannelManager;
import com.wizbl.common.overlay.server.MessageQueue;
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
import com.wizbl.core.net.message.MessageTypes;
import com.wizbl.core.net.message.TransactionMessage;
import com.wizbl.core.net.node.NodeImpl.PriorItem;
import com.wizbl.core.net.node.override.HandshakeHandlerTest;
import com.wizbl.core.net.node.override.PeerClientTest;
import com.wizbl.core.net.node.override.Brte2ChannelInitializerTest;
import com.wizbl.core.net.peer.PeerConnection;
import com.wizbl.core.services.RpcApiService;
import com.wizbl.core.services.WitnessService;
import com.wizbl.protos.Protocol.Block;
import com.wizbl.protos.Protocol.Inventory.InventoryType;
import com.wizbl.protos.Protocol.Transaction;
import com.google.common.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

// TEST CLEAR
@Slf4j
public class BroadTest {

    private static final String dbPath = "output-nodeImplTest-broad";
    private static final String dbDirectory = "db_Broad_test";
    private static final String indexDirectory = "index_Broad_test";
    private static boolean go = false;
    private Brte2ApplicationContext context;
    private NodeImpl nodeImpl;
    private RpcApiService rpcApiService;
    private PeerClientTest peerClient;
    private ChannelManager channelManager;
    private SyncPool pool;
    private Application appT;
    private HandshakeHandlerTest handshakeHandlerTest;
    private Node node;

    private Sha256Hash testBlockBroad() {
        Block block = Block.getDefaultInstance();
        BlockMessage blockMessage = new BlockMessage(new BlockCapsule(block));
        nodeImpl.broadcast(blockMessage);
        ConcurrentHashMap<Sha256Hash, InventoryType> advObjToSpread = ReflectUtils
                .getFieldValue(nodeImpl, "advObjToSpread");
        Assert.assertEquals(advObjToSpread.get(blockMessage.getMessageId()), InventoryType.BLOCK);
        return blockMessage.getMessageId();
    }

    private Sha256Hash testTransactionBroad() {
        Transaction transaction = Transaction.getDefaultInstance();
        TransactionMessage transactionMessage = new TransactionMessage(transaction);
        nodeImpl.broadcast(transactionMessage);
        ConcurrentHashMap<Sha256Hash, InventoryType> advObjToSpread = ReflectUtils
                .getFieldValue(nodeImpl, "advObjToSpread");
        Assert.assertEquals(advObjToSpread.get(transactionMessage.getMessageId()), InventoryType.TRX);
        return transactionMessage.getMessageId();
    }

    private Condition testConsumerAdvObjToSpread() {
        Sha256Hash blockId = testBlockBroad();
        Sha256Hash transactionId = testTransactionBroad();
        //remove the tx and block
        removeTheTxAndBlock(blockId, transactionId);

        ReflectUtils.invokeMethod(nodeImpl, "consumerAdvObjToSpread");
        Collection<PeerConnection> activePeers = ReflectUtils.invokeMethod(nodeImpl, "getActivePeer");

        boolean result = true;
        for (PeerConnection peerConnection : activePeers) {
            if (!peerConnection.getAdvObjWeSpread().containsKey(blockId)) {
                result &= false;
            }
            if (!peerConnection.getAdvObjWeSpread().containsKey(transactionId)) {
                result &= false;
            }
        }
        for (PeerConnection peerConnection : activePeers) {
            peerConnection.getAdvObjWeSpread().clear();
        }
        Assert.assertTrue(result);
        return new Condition(blockId, transactionId);
    }

    private void removeTheTxAndBlock(Sha256Hash blockId, Sha256Hash transactionId) {
        Cache<Sha256Hash, TransactionMessage> trxCache = ReflectUtils
                .getFieldValue(nodeImpl, "TrxCache");
        Cache<Sha256Hash, BlockMessage> blockCache = ReflectUtils.getFieldValue(nodeImpl, "BlockCache");
        trxCache.invalidate(transactionId);
        blockCache.invalidate(blockId);
    }

    @Test
    public void testConsumerAdvObjToFetch() throws InterruptedException {
        Condition condition = testConsumerAdvObjToSpread();
        Thread.sleep(1000);
        //
        Map<Sha256Hash, PriorItem> advObjToFetch = ReflectUtils
                .getFieldValue(nodeImpl, "advObjToFetch");
        logger.info("advObjToFetch:{}", advObjToFetch);
        logger.info("advObjToFetchSize:{}", advObjToFetch.size());
        //Assert.assertEquals(advObjToFetch.get(condition.getBlockId()), InventoryType.BLOCK);
        //Assert.assertEquals(advObjToFetch.get(condition.getTransactionId()), InventoryType.WBL);
        //To avoid writing the database, manually stop the sending of messages.
        Collection<PeerConnection> activePeers = ReflectUtils.invokeMethod(nodeImpl, "getActivePeer");
        Thread.sleep(1000);
        if (activePeers.size() < 1) {
            return;
        }
        for (PeerConnection peerConnection : activePeers) {
            MessageQueue messageQueue = ReflectUtils.getFieldValue(peerConnection, "msgQueue");
            ReflectUtils.setFieldValue(messageQueue, "sendMsgFlag", false);
        }
        //
        ReflectUtils.invokeMethod(nodeImpl, "consumerAdvObjToFetch");
        Thread.sleep(1000);
        boolean result = true;
        int count = 0;
        for (PeerConnection peerConnection : activePeers) {
            if (peerConnection.getAdvObjWeRequested()
                    .containsKey(new Item(condition.getTransactionId(), InventoryType.TRX))) {
                ++count;
            }
            if (peerConnection.getAdvObjWeRequested()
                    .containsKey(new Item(condition.getBlockId(), InventoryType.BLOCK))) {
                ++count;
            }
            MessageQueue messageQueue = ReflectUtils.getFieldValue(peerConnection, "msgQueue");
            BlockingQueue<Message> msgQueue = ReflectUtils.getFieldValue(messageQueue, "msgQueue");
            for (Message message : msgQueue) {
                if (message.getType() == MessageTypes.BLOCK) {
                    Assert.assertEquals(message.getMessageId(), condition.getBlockId());
                }
                if (message.getType() == MessageTypes.TRX) {
                    Assert.assertEquals(message.getMessageId(), condition.getTransactionId());
                }
            }
        }
        Assert.assertTrue(count >= 1);
    }

    @Before
    public void init() {
        node = new Node(
                "enode://e437a4836b77ad9d9ffe73ee782ef2614e6d8370fcf62191a6e488276e23717147073a7ce0b444d485fff5a0c34c4577251a7a990cf80d8542e21b95aa8c5e6c@127.0.0.1:17889");

        new Thread(new Runnable() {
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
                cfgArgs.setNodeListenPort(17889);
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
                nodeImpl = context.getBean(NodeImpl.class);
                peerClient = context.getBean(PeerClientTest.class);
                channelManager = context.getBean(ChannelManager.class);
                pool = context.getBean(SyncPool.class);
                Manager dbManager = context.getBean(Manager.class);
                handshakeHandlerTest = context.getBean(HandshakeHandlerTest.class);
                handshakeHandlerTest.setNode(node);
                NodeDelegate nodeDelegate = new NodeDelegateImpl(dbManager);
                nodeImpl.setNodeDelegate(nodeDelegate);
                pool.init(nodeImpl);
                prepare();
                rpcApiService.blockUntilShutdown();
            }
        }).start();
        int tryTimes = 1;
        while (tryTimes <= 30 && (nodeImpl == null || peerClient == null
                || channelManager == null || pool == null || !go)) {
            try {
                logger.info("nodeImpl:{},peerClient:{},channelManager:{},pool:{},{}", nodeImpl, peerClient,
                        channelManager, pool, go);
                Thread.sleep(1000 * tryTimes);
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                ++tryTimes;
            }
        }
    }

    private void prepare() {
        try {
            ExecutorService advertiseLoopThread = ReflectUtils.getFieldValue(nodeImpl, "broadPool");
            advertiseLoopThread.shutdownNow();

            peerClient.prepare(node.getHexId());

            ReflectUtils.setFieldValue(nodeImpl, "isAdvertiseActive", false);
            ReflectUtils.setFieldValue(nodeImpl, "isFetchActive", false);
            Brte2ChannelInitializerTest brte2ChannelInitializer = ReflectUtils
                    .getFieldValue(peerClient, "brte2ChannelInitializer");
            brte2ChannelInitializer.prepare();
            Channel channel = ReflectUtils.getFieldValue(brte2ChannelInitializer, "channel");
            ReflectUtils.setFieldValue(channel, "handshakeHandler", handshakeHandlerTest);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    peerClient.connect(node.getHost(), node.getPort(), node.getHexId());
                }
            }).start();
            Thread.sleep(2000);
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

    @After
    public void destroy() {
        Args.clearParam();
        Collection<PeerConnection> peerConnections = ReflectUtils
                .invokeMethod(nodeImpl, "getActivePeer");
        for (PeerConnection peer : peerConnections) {
            peer.close();
        }
        context.destroy();
        handshakeHandlerTest.close();
        appT.shutdownServices();
        appT.shutdown();
        FileUtil.deleteDir(new File(dbPath));
    }

    private class Condition {

        private final Sha256Hash blockId;
        private final Sha256Hash transactionId;

        public Condition(Sha256Hash blockId, Sha256Hash transactionId) {
            this.blockId = blockId;
            this.transactionId = transactionId;
        }

        public Sha256Hash getBlockId() {
            return blockId;
        }

        public Sha256Hash getTransactionId() {
            return transactionId;
        }

    }

}
