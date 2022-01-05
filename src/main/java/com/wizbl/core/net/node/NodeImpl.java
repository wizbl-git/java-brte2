package com.wizbl.core.net.node;

import com.wizbl.common.overlay.discover.node.statistics.MessageCount;
import com.wizbl.common.overlay.message.Message;
import com.wizbl.common.overlay.server.Channel.Brte2State;
import com.wizbl.common.overlay.server.SyncPool;
import com.wizbl.common.utils.ExecutorLoop;
import com.wizbl.common.utils.Sha256Hash;
import com.wizbl.common.utils.SlidingWindowCounter;
import com.wizbl.common.utils.Time;
import com.wizbl.core.capsule.BlockCapsule;
import com.wizbl.core.capsule.BlockCapsule.BlockId;
import com.wizbl.core.capsule.TransactionCapsule;
import com.wizbl.core.config.Parameter.ChainConstant;
import com.wizbl.core.config.Parameter.NetConstants;
import com.wizbl.core.config.Parameter.NodeConstant;
import com.wizbl.core.config.args.Args;
import com.wizbl.core.exception.*;
import com.wizbl.core.exception.P2pException.TypeEnum;
import com.wizbl.core.net.message.*;
import com.wizbl.core.net.peer.PeerConnection;
import com.wizbl.core.net.peer.PeerConnectionDelegate;
import com.wizbl.core.services.WitnessProductBlockService;
import com.wizbl.protos.Protocol;
import com.wizbl.protos.Protocol.Inventory.InventoryType;
import com.wizbl.protos.Protocol.ReasonCode;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import javafx.util.Pair;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static com.wizbl.core.config.Parameter.ChainConstant.BLOCK_PRODUCED_INTERVAL;
import static com.wizbl.core.config.Parameter.NetConstants.MAX_TRX_PER_PEER;
import static com.wizbl.core.config.Parameter.NetConstants.MSG_CACHE_DURATION_IN_BLOCKS;
import static com.wizbl.core.config.Parameter.NodeConstant.MAX_BLOCKS_SYNC_FROM_ONE_PEER;

/**
 * NodeImpl 클래스에서는 node가 수행해야 할 역할을 정의한 것으로 보여짐.<br/>
 * 역할
 * &emsp 다른 노드에게 connection을 요청하는 부분 <br/>
 * &emsp connection된 node 즉 peer에 데이터를 전송하는 부분 <br/>
 * &emsp peer로부터 전달받은 데이터를 처리하는 부분
 */
@Slf4j
@Component
public class NodeImpl extends PeerConnectionDelegate implements Node {

    @Autowired
    private TrxHandler trxHandler;

    @Autowired
    private SyncPool pool;

    @Autowired
    private WitnessProductBlockService witnessProductBlockService;

    private final MessageCount trxCount = new MessageCount();

    private final Cache<Sha256Hash, TransactionMessage> TrxCache = CacheBuilder.newBuilder()
            .maximumSize(50_000).expireAfterWrite(1, TimeUnit.HOURS).initialCapacity(50_000)
            .recordStats().build();

    private final Cache<Sha256Hash, BlockMessage> BlockCache = CacheBuilder.newBuilder()
            .maximumSize(10).expireAfterWrite(60, TimeUnit.SECONDS)
            .recordStats().build();

    private final SlidingWindowCounter fetchWaterLine =
            new SlidingWindowCounter(BLOCK_PRODUCED_INTERVAL * MSG_CACHE_DURATION_IN_BLOCKS / 100);

    private final int maxTrxsSize = 1_000_000;

    private final int maxTrxsCnt = 100;

    private final long blockUpdateTimeout = 20_000;

    private final Object syncBlock = new Object();
    private final ScheduledExecutorService logExecutor = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService trxsHandlePool = Executors.newFixedThreadPool(
            Args.getInstance().getValidateSignThreadNum(),
            new ThreadFactoryBuilder().setNameFormat("TrxsHandlePool-%d").build());
    private final Queue<BlockId> freshBlockId = new ConcurrentLinkedQueue<BlockId>() {
        @Override
        public boolean offer(BlockId blockId) {
            if (size() > 200) {
                super.poll();
            }
            return super.offer(blockId);
        }
    };
    private final ConcurrentHashMap<Sha256Hash, PeerConnection> syncMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Sha256Hash, PeerConnection> fetchMap = new ConcurrentHashMap<>();
    private NodeDelegate del;
    private volatile boolean isAdvertiseActive;
    private volatile boolean isFetchActive;
    private final ScheduledExecutorService disconnectInactiveExecutor = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService cleanInventoryExecutor = Executors.newSingleThreadScheduledExecutor();
    //broadcast
    private final ConcurrentHashMap<Sha256Hash, InventoryType> advObjToSpread = new ConcurrentHashMap<>();
    private final HashMap<Sha256Hash, Long> advObjWeRequested = new HashMap<>();
    private final ConcurrentHashMap<Sha256Hash, PriorItem> advObjToFetch = new ConcurrentHashMap<Sha256Hash, PriorItem>();
    private final ExecutorService broadPool = Executors.newFixedThreadPool(2, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, "broad-msg");
        }
    });
    private final Cache<BlockId, Long> syncBlockIdWeRequested = CacheBuilder.newBuilder()
            .maximumSize(10000).expireAfterWrite(1, TimeUnit.HOURS).initialCapacity(10000)
            .recordStats().build();
    private Long unSyncNum = 0L;
    private final Map<BlockMessage, PeerConnection> blockWaitToProc = new ConcurrentHashMap<>();
    private final Map<BlockMessage, PeerConnection> blockJustReceived = new ConcurrentHashMap<>();
    private ExecutorLoop<SyncBlockChainMessage> loopSyncBlockChain;
    private ExecutorLoop<FetchInvDataMessage> loopFetchBlocks;
    private ExecutorLoop<Message> loopAdvertiseInv;
    private final ScheduledExecutorService fetchSyncBlocksExecutor = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService handleSyncBlockExecutor = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService fetchWaterLineExecutor = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean isHandleSyncBlockActive = false;
    private final AtomicLong fetchSequenceCounter = new AtomicLong(0L);
    private volatile boolean isSuspendFetch = false;
    private volatile boolean isFetchSyncActive = false;

    /**
     * Brte2Handler를 통해서 전달받은 이벤트를 처리하는 메소드
     * 이 부분은 직접 테스트 환경을 구축해서 돌려보는 것이 제일 좋아보임.
     * @param peer
     * @param msg
     * @throws Exception
     */
    @Override
    public void onMessage(PeerConnection peer, Brte2Message msg) throws Exception {
        switch (msg.getType()) {
            case BLOCK:
                onHandleBlockMessage(peer, (BlockMessage) msg); // peer로부터 블록 정보를 전달받았을 경우, 단 sync는 false 상태로 이후 작업이 진행됨.
                break;
            case TRXS:
                trxHandler.handleTransactionsMessage(peer, (TransactionsMessage) msg); //msg에는 TransactionList의 형태로 내용이 전달됨.
                break;
            case SYNC_BLOCK_CHAIN:
                onHandleSyncBlockChainMessage(peer, (SyncBlockChainMessage) msg); // TODO -
                break;
            case FETCH_INV_DATA:
                onHandleFetchDataMessage(peer, (FetchInvDataMessage) msg);
                break;
            case BLOCK_CHAIN_INVENTORY: // blockChain 연동과 관련해서 message를 주고 받을 때 실행됨.
                onHandleChainInventoryMessage(peer, (ChainInventoryMessage) msg);
                break;
            case INVENTORY:
                onHandleInventoryMessage(peer, (InventoryMessage) msg);
                break;
            default:
                throw new P2pException(TypeEnum.NO_SUCH_MESSAGE, "msg type: " + msg.getType());
        }
    }

    @Override
    public Message getMessage(Sha256Hash msgId) {
        return null;
    }

    @Override
    public void setNodeDelegate(NodeDelegate nodeDel) {
        this.del = nodeDel;
    }

    // for test only
    public void setPool(SyncPool pool) {
        this.pool = pool;
    }

    /**
     * broadcasting 되어야할 대상에 따라서 Block과 Trnasaction(TRX) 타입으로 구분된다.<br/>
     * type별로 Block은 BlockCache , trx는 TrxCache에  messageId와 msg를 저장함.  <br/>
     * 또한 전달된 msg는 advObjToSpread에 저장되며, 이 advObjToSpread consumerAdvObjToSpread메소드에서 사용됨.<br/>
     * 이 메소드에서는 실질적인 broadcasting은 발생하지 않는다. MessageQueue에서 외부로 send하기 위해서 필요한 trxMsg를 advObjToSpread에 저장함.
     *
     * @param msg
     */
    public void broadcast(Message msg) {
        InventoryType type;
        if (msg instanceof BlockMessage) {
            logger.info("Ready to broadcast block {}", ((BlockMessage) msg).getBlockId());
            freshBlockId.offer(((BlockMessage) msg).getBlockId());
            BlockCache.put(msg.getMessageId(), (BlockMessage) msg);
            type = InventoryType.BLOCK;
        } else if (msg instanceof TransactionMessage) {
            TrxCache.put(msg.getMessageId(), (TransactionMessage) msg);
            type = InventoryType.TRX;
        } else {
            return;
        }
        synchronized (advObjToSpread) {
            advObjToSpread.put(msg.getMessageId(), type);
        }
    }

    /**
     * Node의 역할과 관련된 객체 및 변수의 초기화 및 node에서 외부로 데이터를 전송하는 pump 기능 활성화
     */
    @Override
    public void listen() {
        pool.init(this);
        trxHandler.init(this);
        isAdvertiseActive = true;
        isFetchActive = true;
        activeBrte2Pump();
    }

    @Override
    public void close() {
        getActivePeer().forEach(peer -> disconnectPeer(peer, ReasonCode.REQUESTED));
    }

    /**
     * Executor 객체 생성 및 해당 executor별 역할 설정 <br/>
     * listen메소드가 호출될 때 같이 호출되는 메소드로서 loopAdvertiseInv, loopFetchBlocks, loopSyncBlockChain <br/>
     * ExecutorService의 job 실행 <br/>
     * handleSyncBlockExecutor, disconnectInactiveExecutor, logExecutor, cleanInventoryExecutor, fetchSyncBlocksExecutor, fetchWaterLineExecutor 객체 생성 <br/>
     * Node 상호 간에 데이터를 주고 받으며, 이를 처리하는 메소드. <br/>
     * 연결된 Peer에게 데이터를 지속적으로 전달하는 역할을 하는 부분으로 추측 됨.(pump니까)
     */
    private void activeBrte2Pump() {

        // start of ExecutorLoop instances

        // TODO - 언제 호출이되는지 확인이 필요함.
        loopAdvertiseInv = new ExecutorLoop<>(2, 10, b -> {
            for (PeerConnection peer : getActivePeer()) {
                if (!peer.isNeedSyncFromUs()) {
                    logger.info("Advertise adverInv to " + peer);
                    peer.sendMessage(b);
                }
            }
        }, throwable -> logger.error("Unhandled exception: ", throwable));

        // fetch blocks
        // TODO - 언제 호출이되는지 확인이 필요함.
        loopFetchBlocks = new ExecutorLoop<>(2, 10, c -> {
            logger.info("loop fetch blocks");
            if (fetchMap.containsKey(c.getMessageId())) {
                fetchMap.get(c.getMessageId()).sendMessage(c);
            }
        }, throwable -> logger.error("Unhandled exception: ", throwable));

        // sync block chain
        // TODO - 언제 호출이되는지 확인이 필요함.
        loopSyncBlockChain = new ExecutorLoop<>(2, 10, d -> {
            if (syncMap.containsKey(d.getMessageId())) {
                syncMap.get(d.getMessageId()).sendMessage(d);
            }
        }, throwable -> logger.error("Unhandled exception: ", throwable));

        // end of ExecutorLoop instances


        // start broadcasting executorService
        broadPool.submit(() -> {
            while (isAdvertiseActive) { // isAdvertiseActive = true 별도로 값을 변경하는 부분은 찾지 못함.
                consumerAdvObjToSpread();
            }
        });

        broadPool.submit(() -> {
            while (isFetchActive) {   // isFetchActive = true 별도로 값을 변경하는 부분은 찾지 못함.
                consumerAdvObjToFetch();
            }
        });
        // end broadcasting executorService


        handleSyncBlockExecutor.scheduleWithFixedDelay(() -> {
            try {
                if (isHandleSyncBlockActive) {
                    isHandleSyncBlockActive = false;
                    handleSyncBlock();
                }
            } catch (Throwable t) {
                logger.error("Unhandled exception", t);
            }
        }, 10, 1, TimeUnit.SECONDS);

        //terminate inactive loop
        disconnectInactiveExecutor.scheduleWithFixedDelay(() -> {
            try {
                disconnectInactive();
            } catch (Throwable t) {
                logger.error("Unhandled exception", t);
            }
        }, 30000, BLOCK_PRODUCED_INTERVAL / 2, TimeUnit.MILLISECONDS);

        logExecutor.scheduleWithFixedDelay(() -> {
            try {
                logNodeStatus();
            } catch (Throwable t) {
                logger.error("Exception in log worker", t);
            }
        }, 10, 10, TimeUnit.SECONDS);

        cleanInventoryExecutor.scheduleWithFixedDelay(() -> {
            try {
                getActivePeer().forEach(p -> p.cleanInvGarbage());
            } catch (Throwable t) {
                logger.error("Unhandled exception", t);
            }
        }, 2, NetConstants.MAX_INVENTORY_SIZE_IN_MINUTES / 2, TimeUnit.MINUTES);

        fetchSyncBlocksExecutor.scheduleWithFixedDelay(() -> {
            try {
                if (isFetchSyncActive) {
                    if (!isSuspendFetch) {
                        startFetchSyncBlock();
                    } else {
                        logger.debug("suspend");
                    }
                }
                isFetchSyncActive = false;
            } catch (Throwable t) {
                logger.error("Unhandled exception", t);
            }
        }, 10, 1, TimeUnit.SECONDS);

        //fetchWaterLine:
        fetchWaterLineExecutor.scheduleWithFixedDelay(() -> {
            try {
                fetchWaterLine.advance();
            } catch (Throwable t) {
                logger.error("Unhandled exception", t);
            }
        }, 1000, 100, TimeUnit.MILLISECONDS);
    }

    private void consumerAdvObjToFetch() {
        Collection<PeerConnection> filterActivePeer = getActivePeer().stream()
                .filter(peer -> !peer.isBusy()).collect(Collectors.toList());

        if (advObjToFetch.isEmpty() || filterActivePeer.isEmpty()) {
            try {
                Thread.sleep(100);
                return;
            } catch (InterruptedException e) {
                logger.debug(e.getMessage(), e);
            }
        }
        InvToSend sendPackage = new InvToSend();
        long now = Time.getCurrentMillis();
        advObjToFetch.values().stream().sorted(PriorItem::compareTo).forEach(idToFetch -> {
            Sha256Hash hash = idToFetch.getHash();
            if (idToFetch.getTime() < now - MSG_CACHE_DURATION_IN_BLOCKS * BLOCK_PRODUCED_INTERVAL) {
                logger.info("This obj is too late to fetch, type: {} hash: {}.", idToFetch.getType(),
                        idToFetch.getHash());
                advObjToFetch.remove(hash);
                return;
            }
            filterActivePeer.stream()
                    .filter(peer -> peer.getAdvObjSpreadToUs().containsKey(hash)
                            && sendPackage.getSize(peer) < MAX_TRX_PER_PEER)
                    .sorted(Comparator.comparingInt(peer -> sendPackage.getSize(peer)))
                    .findFirst().ifPresent(peer -> {
                sendPackage.add(idToFetch, peer);
                peer.getAdvObjWeRequested().put(idToFetch.getItem(), now);
                advObjToFetch.remove(hash);
            });
        });

        sendPackage.sendFetch();
    }

    /**
     * advObjToSpread 객체에 저장된 내용을 sendPackage 객체에 저장하여 activePeer에 sending하는 메소드
     */
    private void consumerAdvObjToSpread() {
        if (advObjToSpread.isEmpty()) {
            try {
                Thread.sleep(100);
                return;
            } catch (InterruptedException e) {
                logger.debug(e.getMessage(), e);
            }
        }
        InvToSend sendPackage = new InvToSend();
        HashMap<Sha256Hash, InventoryType> spread = new HashMap<>();
        synchronized (advObjToSpread) {
            spread.putAll(advObjToSpread);
            advObjToSpread.clear();
        }
        for (InventoryType type : spread.values()) {
            if (type == InventoryType.TRX) {
                trxCount.add();
            }
        }
        getActivePeer().stream()
                .filter(peer -> !peer.isNeedSyncFromUs())
                .forEach(peer ->
                        spread.entrySet().stream()
                                .filter(idToSpread ->
                                        !peer.getAdvObjSpreadToUs().containsKey(idToSpread.getKey())
                                                && !peer.getAdvObjWeSpread().containsKey(idToSpread.getKey()))
                                .forEach(idToSpread -> {
                                    peer.getAdvObjWeSpread().put(idToSpread.getKey(), Time.getCurrentMillis());
                                    sendPackage.add(idToSpread, peer);
                                }));
        sendPackage.sendInv();
    }

    private synchronized void handleSyncBlock() {
        if (isSuspendFetch) {
            isSuspendFetch = false;
        }

        final boolean[] isBlockProc = {true};

        while (isBlockProc[0]) {

            isBlockProc[0] = false;

            synchronized (blockJustReceived) {
                blockWaitToProc.putAll(blockJustReceived);
                blockJustReceived.clear();
            }

            blockWaitToProc.forEach((msg, peerConnection) -> {

                if (peerConnection.isDisconnect()) {
                    logger.error("Peer {} is disconnect, drop block {}", peerConnection.getNode().getHost(),
                            msg.getBlockId().getString());
                    blockWaitToProc.remove(msg);
                    syncBlockIdWeRequested.invalidate(msg.getBlockId());
                    isFetchSyncActive = true;
                    return;
                }

                synchronized (freshBlockId) {
                    final boolean[] isFound = {false};
                    getActivePeer().stream()
                            .filter(peer -> !peer.getSyncBlockToFetch().isEmpty() && peer.getSyncBlockToFetch().peek().equals(msg.getBlockId()))
                            .forEach(peer -> {
                                peer.getSyncBlockToFetch().pop();
                                peer.getBlockInProc().add(msg.getBlockId());
                                isFound[0] = true;
                            });
                    if (isFound[0]) {
                        blockWaitToProc.remove(msg);
                        isBlockProc[0] = true;
                        if (freshBlockId.contains(msg.getBlockId()) || processSyncBlock(msg.getBlockCapsule())) {
                            finishProcessSyncBlock(msg.getBlockCapsule());
                        }
                    }
                }
            });

        }
    }

    private synchronized void logNodeStatus() {
        StringBuilder sb = new StringBuilder("LocalNode stats:\n");
        sb.append("============\n");

        sb.append(String.format(
                "MyHeadBlockNum: %d\n"
                        + "advObjToSpread: %d\n"
                        + "advObjToFetch: %d\n"
                        + "advObjWeRequested: %d\n"
                        + "unSyncNum: %d\n"
                        + "blockWaitToProc: %d\n"
                        + "blockJustReceived: %d\n"
                        + "syncBlockIdWeRequested: %d\n",
                del.getHeadBlockId().getNum(),
                advObjToSpread.size(),
                advObjToFetch.size(),
                advObjWeRequested.size(),
                getUnSyncNum(),
                blockWaitToProc.size(),
                blockJustReceived.size(),
                syncBlockIdWeRequested.size()
        ));

        logger.info(sb.toString());
    }

    public synchronized void disconnectInactive() {

        getActivePeer().forEach(peer -> {
            final boolean[] isDisconnected = {false};

            if (peer.isNeedSyncFromPeer()
                    && peer.getLastBlockUpdateTime() < System.currentTimeMillis() - blockUpdateTimeout) {
                logger.warn("Peer {} not sync for a long time.", peer.getInetAddress());
                isDisconnected[0] = true;
            }

            peer.getAdvObjWeRequested().values().stream()
                    .filter(time -> time < Time.getCurrentMillis() - NetConstants.ADV_TIME_OUT)
                    .findFirst().ifPresent(time -> isDisconnected[0] = true);

            if (!isDisconnected[0]) {
                peer.getSyncBlockRequested().values().stream()
                        .filter(time -> time < Time.getCurrentMillis() - NetConstants.SYNC_TIME_OUT)
                        .findFirst().ifPresent(time -> isDisconnected[0] = true);
            }

            if (isDisconnected[0]) {
                disconnectPeer(peer, ReasonCode.TIME_OUT);
            }
        });
    }

    private void onHandleInventoryMessage(PeerConnection peer, InventoryMessage msg) {
        int count = peer.getNodeStatistics().messageStatistics.brte2InTrxInventoryElement.getCount(10);
        if (count > 10_000) {
            logger.warn("Inventory count {} from Peer {} is overload.", count, peer.getInetAddress());
            return;
        }
        if (trxHandler.isBusy() && msg.getInventoryType().equals(InventoryType.TRX)) {
            logger.warn("Too many trx msg to handle, drop inventory msg from peer {}, size {}",
                    peer.getInetAddress(), msg.getHashList().size());
            return;
        }
        for (Sha256Hash id : msg.getHashList()) {
            if (msg.getInventoryType().equals(InventoryType.TRX) && TrxCache.getIfPresent(id) != null) {
                continue;
            }
            final boolean[] spreaded = {false};
            final boolean[] requested = {false};
            getActivePeer().forEach(p -> {
                if (p.getAdvObjWeSpread().containsKey(id)) {
                    spreaded[0] = true;
                }
                if (p.getAdvObjWeRequested().containsKey(new Item(id, msg.getInventoryType()))) {
                    requested[0] = true;
                }
            });

            if (!spreaded[0]
                    && !peer.isNeedSyncFromPeer()
                    && !peer.isNeedSyncFromUs()) {

                //avoid WBL flood attack here.
//        if (msg.getInventoryType().equals(InventoryType.WBL)
//            && (peer.isAdvInvFull() || isFlooded())) {
//          logger.warn("A peer is flooding us, stop handle inv, the peer is: " + peer);
//          return;
//        }

                peer.getAdvObjSpreadToUs().put(id, System.currentTimeMillis());
                if (!requested[0]) {
                    PriorItem targetPriorItem = this.advObjToFetch.get(id);
                    if (targetPriorItem != null) {
                        //another peer tell this trx to us, refresh its time.
                        targetPriorItem.refreshTime();
                    } else {
                        fetchWaterLine.increase();
                        this.advObjToFetch.put(id, new PriorItem(new Item(id, msg.getInventoryType()),
                                fetchSequenceCounter.incrementAndGet()));
                    }
                }
            }
        }
    }

    private boolean isFlooded() {
        return fetchWaterLine.totalCount()
                > BLOCK_PRODUCED_INTERVAL
                * Args.getInstance().getNetMaxTrxPerSecond()
                * MSG_CACHE_DURATION_IN_BLOCKS
                / 1000;
    }

    @Override
    public void syncFrom(Sha256Hash myHeadBlockHash) { //TODO - myHeadBlockHash는 왜 가져온거야????
        try {
            while (getActivePeer().isEmpty()) {
                logger.info("other peer is nil, please wait ... ");
                Thread.sleep(10000L);
            }
        } catch (InterruptedException e) {
            logger.debug(e.getMessage(), e);
        }
        logger.info("wait end");
    }

    /**
     * 전달받은 Block정보와 보유하고 있는 블록 간에 sync를 맞추기 위한 메소드 <br/>
     * fetch의 개념을 sync 해야할 파일은 전달받았지만 아직 sync작업(병합,동기화 작업)은 하지 않은 상태로 이해하고 있음.
     *
     * @param peer
     * @param blkMsg
     */
    private void onHandleBlockMessage(PeerConnection peer, BlockMessage blkMsg) {
        Map<Item, Long> advObjWeRequested = peer.getAdvObjWeRequested();
        Map<BlockId, Long> syncBlockRequested = peer.getSyncBlockRequested(); // sync를 하고자 하는 블록 목록
        BlockId blockId = blkMsg.getBlockId();
        Item item = new Item(blockId, InventoryType.BLOCK);
        boolean syncFlag = false;
        if (syncBlockRequested.containsKey(blockId)) { // sync를 해야하는 블록 가운데 blockId가 존재하는 경우
            if (!peer.getSyncFlag()) {
                logger.info("Received a block {} from no need sync peer {}", blockId.getNum(), peer.getNode().getHost());
                return;
            }

            // sync가 필요하다면 blockId에 해당되는 정보를 syncBlockRequested에서 삭제하고, blockJustReceived 항목에 저장함.
            // blockJustReceived 객체는 실제 sync 과정에서 활용되는 객체임.
            peer.getSyncBlockRequested().remove(blockId);
            synchronized (blockJustReceived) {
                blockJustReceived.put(blkMsg, peer);
            }
            isHandleSyncBlockActive = true;
            syncFlag = true;
            if (!peer.isBusy()) {
                if (peer.getUnfetchSyncNum() > 0 && peer.getSyncBlockToFetch().size() <= NodeConstant.SYNC_FETCH_BATCH_NUM) {
                    syncNextBatchChainIds(peer);
                } else {
                    isFetchSyncActive = true;
                }
            }
        }

        if (advObjWeRequested.containsKey(item)) {
            advObjWeRequested.remove(item);
            if (!syncFlag) {
                processAdvBlock(peer, blkMsg.getBlockCapsule());
                startFetchItem();
            }
        }
    }

    /**
     * sync되지 않는 블록 정보가 전달되었을 때 node에서 해당 블록을 처리하는 메소드 <br/>
     * &emsp 1. 전달받은 블록을 freshBlockId에 추가 <br/>
     * &emsp 2. 전달받은 블록에 포함된 txId를 adbObjFetch 객체에서 제거 이유는??? <br/>
     * &emsp 3. activePeer에서 우리에게 전달된 블록인지 여부를 검증한 후 피어간에 동일한 block 정보를 가지고 있음을 갱신 <br/>
     * &emsp 4. broadcast
     *
     * @param peer
     * @param block
     */
    private void processAdvBlock(PeerConnection peer, BlockCapsule block) {
        synchronized (freshBlockId) {
            if (!freshBlockId.contains(block.getBlockId())) {
                try {
                    witnessProductBlockService.validWitnessProductTwoBlock(block);
                    LinkedList<Sha256Hash> trxIds = null;

                    logger.info("********************************* NodeImpl.processAdvBlock *******************************");

                    trxIds = del.handleBlock(block, false);
                    freshBlockId.offer(block.getBlockId());

                    trxIds.forEach(trxId -> advObjToFetch.remove(trxId));

                    // activePeer에서 peer에 blockId가 포함되어 있으며 peer에게 해당 block은 상호 보유하고 있는 블록으로 설정함.
                    // TODO - blockWeBothHave 관련된 내용은 향후 데이터 전송관련해서 불필요 데이터를 전송하는 오버헤드를 줄여주는 용도로 사용될 것으로 보여짐.
                    getActivePeer().stream()
                            .filter(p -> p.getAdvObjSpreadToUs().containsKey(block.getBlockId()))
                            .forEach(p -> updateBlockWeBothHave(p, block));

                    broadcast(new BlockMessage(block));

                } catch (BadBlockException e) {
                    logger.error("We get a bad block {}, from {}, reason is {} ",
                            block.getBlockId().getString(), peer.getNode().getHost(), e.getMessage());
                    disconnectPeer(peer, ReasonCode.BAD_BLOCK);
                } catch (UnLinkedBlockException e) {
                    logger.error("We get a unlinked block {}, from {}, head is {}", block.getBlockId().
                            getString(), peer.getNode().getHost(), del.getHeadBlockId().getString());
                    startSyncWithPeer(peer);
                } catch (NonCommonBlockException e) {
                    logger.error(
                            "We get a block {} that do not have the most recent common ancestor with the main chain, from {}, reason is {} ",
                            block.getBlockId().getString(), peer.getNode().getHost(), e.getMessage());
                    disconnectPeer(peer, ReasonCode.FORKED);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private boolean processSyncBlock(BlockCapsule block) {
        boolean isAccept = false;
        ReasonCode reason = null;
        try {
            try {

                logger.info("*********************** NodeImpl.processSyncBlock ****************************");

                del.handleBlock(block, true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            freshBlockId.offer(block.getBlockId());
            logger.info("Success handle block {}", block.getBlockId().getString());
            isAccept = true;
        } catch (BadBlockException e) {
            logger.error("We get a bad block {}, reason is {} ", block.getBlockId().getString(),
                    e.getMessage());
            reason = ReasonCode.BAD_BLOCK;
        } catch (UnLinkedBlockException e) {
            logger.error("We get a unlinked block {}, head is {}", block.getBlockId().getString(),
                    del.getHeadBlockId().getString());
            reason = ReasonCode.UNLINKABLE;
        } catch (NonCommonBlockException e) {
            logger.error(
                    "We get a block {} that do not have the most recent common ancestor with the main chain, head is {}",
                    block.getBlockId().getString(),
                    del.getHeadBlockId().getString());
            reason = ReasonCode.FORKED;
        }

        if (!isAccept) {
            ReasonCode finalReason = reason;
            getActivePeer().stream()
                    .filter(peer -> peer.getBlockInProc().contains(block.getBlockId()))
                    .forEach(peer -> disconnectPeer(peer, finalReason));
        }
        isHandleSyncBlockActive = true;
        return isAccept;
    }

    private void finishProcessSyncBlock(BlockCapsule block) {
        getActivePeer().forEach(peer -> {
            if (peer.getSyncBlockToFetch().isEmpty()
                    && peer.getBlockInProc().isEmpty()
                    && !peer.isNeedSyncFromPeer()
                    && !peer.isNeedSyncFromUs()) {
                startSyncWithPeer(peer);
            } else if (peer.getBlockInProc().remove(block.getBlockId())) {
                updateBlockWeBothHave(peer, block);
                if (peer.getSyncBlockToFetch().isEmpty()) { //send sync to let peer know we are sync.
                    syncNextBatchChainIds(peer);
                }
            }
        });
    }

    /**
     * TrxCache에 trxMsg가 존재하면 true, 아니면 false를 반환함. <br/>
     * 저장되어 있지 않은 경우에는 trxMsg를 TrxCache에 저장함.
     *
     * @param trxMsg
     * @return
     */
    synchronized boolean isTrxExist(TransactionMessage trxMsg) {
        if (TrxCache.getIfPresent(trxMsg.getMessageId()) != null) {
            return true;
        }
        TrxCache.put(trxMsg.getMessageId(), trxMsg);
        return false;
    }

    /**
     * trxMsg가 이미 처리되었는지 여부를 확인한 후 처리 전이면 transactionCapsule을 pendingTransaction에 저장하고, trxMsg를 broadcast하기 위한 과정에 들어간다.
     *
     * @param peer
     * @param trxMsg
     */
    public void onHandleTransactionMessage(PeerConnection peer, TransactionMessage trxMsg) {
        try {
            if (isTrxExist(trxMsg)) {
                logger.info("Trx {} from Peer {} already processed.", trxMsg.getMessageId(), peer.getNode().getHost());
                return;
            }

            TransactionCapsule transactionCapsule = trxMsg.getTransactionCapsule();

            // 블록 생성을 하는 경우에는 pushTransaction을 호출하여 pendingTransaction에 저장하고
            // 블록 생성을 하지 않는 경우에는 해당 transactionCapsule을 다른 노드로 전파해야 함.
            if (del.handleTransaction(transactionCapsule)) { // transactionCapsule을 pendingTransaction에 저장함.
                broadcast(trxMsg);
            }
        } catch (BadTransactionException e) {
            logger.error("Bad Trx {} from peer {}, error: {}", trxMsg.getMessageId(), peer.getInetAddress(), e.getMessage());
            banTraitorPeer(peer, ReasonCode.BAD_TX);
        } catch (Exception e) {
            logger.error("Process trx {} from peer {} failed",
                    trxMsg.getMessageId(), peer.getInetAddress(), e);
        }
    }

    /**
     * syncBlock으로부터 확인된 sync해야할 블록의 마지막 블록 번호(lastBlockNum)가 peer로부터 확인한 lastSyncBlock 블록 번호(lastSyncBlockId.getNum())의 크기를 비교하여 sync여부를 체크함.
     * lastBlocNum > lastSyncBlockId.getNum();
     *
     * @param peer
     * @param syncMsg
     * @return
     */
    private boolean checkSyncBlockChainMessage(PeerConnection peer, SyncBlockChainMessage syncMsg) {
        long lastBlockNum = syncMsg.getBlockIds().get(syncMsg.getBlockIds().size() - 1).getNum();
        BlockId lastSyncBlockId = peer.getLastSyncBlockId();
        if (lastSyncBlockId != null && lastBlockNum < lastSyncBlockId.getNum()) {
            logger.warn("Peer {} receive bad SyncBlockChain message, firstNum {} lastSyncNum {}.",
                    peer.getInetAddress(), lastBlockNum, lastSyncBlockId.getNum());
            return false;
        }
        return true;
    }

    private void onHandleSyncBlockChainMessage(PeerConnection peer, SyncBlockChainMessage syncMsg) {
        peer.setBrte2State(Brte2State.SYNCING);
        BlockId headBlockId = del.getHeadBlockId();
        long remainNum = 0;
        LinkedList<BlockId> blockIds = new LinkedList<>();
        // summaryChainIds는 sync 할 blockIds를 의미함.
        List<BlockId> summaryChainIds = syncMsg.getBlockIds();
        // 1. syncBlock의 조건 검증
        if (!checkSyncBlockChainMessage(peer, syncMsg)) {
            disconnectPeer(peer, ReasonCode.BAD_PROTOCOL);
            return;
        }
        try {
            blockIds = del.getLostBlockIds(summaryChainIds);
        } catch (StoreException e) {
            logger.error(e.getMessage());
        }

        if (blockIds.isEmpty()) {
            if (CollectionUtils.isNotEmpty(summaryChainIds) &&
                    !del.canChainRevoke(summaryChainIds.get(0).getNum())) {
                logger.info("Node sync block fail, disconnect peer {}, no block {}", peer,
                        summaryChainIds.get(0).getString());
                peer.disconnect(ReasonCode.SYNC_FAIL);
                return;
            } else {
                peer.setNeedSyncFromUs(false);
            }
        } else if (blockIds.size() == 1
                    && !summaryChainIds.isEmpty()
                    && (summaryChainIds.contains(blockIds.peekFirst())
                    || blockIds.peek().getNum() == 0)) {
            peer.setNeedSyncFromUs(false);
        } else {
            peer.setNeedSyncFromUs(true);
            remainNum = del.getHeadBlockId().getNum() - blockIds.peekLast().getNum();
        }

        if (!peer.isNeedSyncFromPeer()
                && CollectionUtils.isNotEmpty(summaryChainIds)
                && !del.contain(Iterables.getLast(summaryChainIds), MessageTypes.BLOCK)
                && del.canChainRevoke(summaryChainIds.get(0).getNum())) {
            startSyncWithPeer(peer);
        }

        if (blockIds.peekLast() == null) {
            peer.setLastSyncBlockId(headBlockId);
        } else {
            peer.setLastSyncBlockId(blockIds.peekLast());
        }
        peer.setRemainNum(remainNum);
        peer.sendMessage(new ChainInventoryMessage(blockIds, remainNum));
    }

    /**
     * FetchInvDataMsg에서는 10초동안에 fetch를 요청한 갯수가 60초 동안에 생성된 trx의 갯수보다 많으면 안됨.
     * 또한 peer는 fetchInvDataMsg에 포함된 hash 키를 서로(we) 가지고 있어야 함.
     *
     * @param peer
     * @param fetchInvDataMsg
     * @return
     */
    private boolean checkFetchInvDataMsg(PeerConnection peer, FetchInvDataMessage fetchInvDataMsg) {
        MessageTypes type = fetchInvDataMsg.getInvMessageType();
        if (type == MessageTypes.TRX) {
            int elementCount = peer.getNodeStatistics().messageStatistics.brte2InTrxFetchInvDataElement.getCount(10);
            int maxCount = trxCount.getCount(60);
            // 10초 동안에 요처하는 element의 갯수보다, 60초동안 생성되는 trx의 갯수보다 적어야 함을 의미한다.
            // 생성된 것보다 더 많은 것을 요청하면 안된다????
            if (elementCount > maxCount) {
                logger.warn(
                        "Check FetchInvDataMsg failed: Peer {} request count {} in 10s gt trx count {} generate in 60s",
                        peer.getInetAddress(), elementCount, maxCount);
                return false;
            }
            for (Sha256Hash hash : fetchInvDataMsg.getHashList()) {
                if (!peer.getAdvObjWeSpread().containsKey(hash)) {
                    logger.warn("Check FetchInvDataMsg failed: Peer {} get trx {} we not spread.", peer.getInetAddress(), hash);
                    return false;
                }
            }
        } else {
            boolean isAdv = true;
            for (Sha256Hash hash : fetchInvDataMsg.getHashList()) {
                if (!peer.getAdvObjWeSpread().containsKey(hash)) {
                    isAdv = false;
                    break;
                }
            }
            if (isAdv) {
                MessageCount brte2OutAdvBlock = peer.getNodeStatistics().messageStatistics.brte2OutAdvBlock;
                brte2OutAdvBlock.add(fetchInvDataMsg.getHashList().size());
                int outBlockCountIn1min = brte2OutAdvBlock.getCount(60);
                int producedBlockIn2min = 120_000 / ChainConstant.BLOCK_PRODUCED_INTERVAL;
                if (outBlockCountIn1min > producedBlockIn2min) {
                    logger
                            .warn("Check FetchInvDataMsg failed: Peer {} outBlockCount {} producedBlockIn2min {}",
                                    peer.getInetAddress(), outBlockCountIn1min, producedBlockIn2min);
                    return false;
                }
            } else {
                if (!peer.isNeedSyncFromUs()) {
                    logger.warn("Check FetchInvDataMsg failed: Peer {} not need sync from us.",
                            peer.getInetAddress());
                    return false;
                }
                for (Sha256Hash hash : fetchInvDataMsg.getHashList()) {
                    long blockNum = new BlockId(hash).getNum();
                    long minBlockNum =
                            peer.getLastSyncBlockId().getNum() - 2 * NodeConstant.SYNC_FETCH_BATCH_NUM;
                    if (blockNum < minBlockNum) {
                        logger.warn("Check FetchInvDataMsg failed: Peer {} blockNum {} lt minBlockNum {}",
                                peer.getInetAddress(), blockNum, minBlockNum);
                        return false;
                    }
                    if (peer.getSyncBlockIdCache().getIfPresent(hash) != null) {
                        logger.warn("Check FetchInvDataMsg failed: Peer {} blockNum {} hash {} is exist",
                                peer.getInetAddress(), blockNum, hash);
                        return false;
                    }
                    peer.getSyncBlockIdCache().put(hash, 1);
                }
            }
        }
        return true;
    }

    /**
     * Fetch가 가지고 오다 의 의미임을 고려할 때 peer로 부터 무엇인가를 가져오는 기능을 수행할 것으로 예상됨.
     * @param peer
     * @param fetchInvDataMsg
     */
    private void onHandleFetchDataMessage(PeerConnection peer, FetchInvDataMessage fetchInvDataMsg) {

        if (!checkFetchInvDataMsg(peer, fetchInvDataMsg)) {
            disconnectPeer(peer, ReasonCode.BAD_PROTOCOL);
            return;
        }

        MessageTypes type = fetchInvDataMsg.getInvMessageType();
        BlockCapsule block = null;
        List<Protocol.Transaction> transactions = Lists.newArrayList();

        int size = 0;

        for (Sha256Hash hash : fetchInvDataMsg.getHashList()) {

            Message msg;

            if (type == MessageTypes.BLOCK) {
                msg = BlockCache.getIfPresent(hash);
            } else {
                msg = TrxCache.getIfPresent(hash);
            }

            if (msg == null) {
                try {
                    msg = del.getData(hash, type);
                } catch (StoreException e) {
                    logger.error("fetch message {} {} failed.", type, hash);
                    peer.sendMessage(new ItemNotFound());
                    //todo,should be disconnect?
                    return;
                }
            }

            if (type.equals(MessageTypes.BLOCK)) {
                block = ((BlockMessage) msg).getBlockCapsule();
                peer.sendMessage(msg);
            } else {
                transactions.add(((TransactionMessage) msg).getTransactionCapsule().getInstance());
                size += ((TransactionMessage) msg).getTransactionCapsule().getInstance()
                        .getSerializedSize();
                if (transactions.size() % maxTrxsCnt == 0 || size > maxTrxsSize) {
                    peer.sendMessage(new TransactionsMessage(transactions));
                    transactions = Lists.newArrayList();
                    size = 0;
                }
            }
        }

        if (block != null) {
            updateBlockWeBothHave(peer, block);
        }
        if (transactions.size() > 0) {
            peer.sendMessage(new TransactionsMessage(transactions));
        }
    }

    /**
     * peer를 ban 시킴
     *
     * @param peer
     * @param reason
     */
    private void banTraitorPeer(PeerConnection peer, ReasonCode reason) {
        disconnectPeer(peer, reason); //TODO: ban it
    }

    /**
     * We의 의미는 peer와 실행중인 node를 포함하는 개념으로 보여짐.
     * @param peer
     * @param msg
     */
    private void onHandleChainInventoryMessage(PeerConnection peer, ChainInventoryMessage msg) {
        try {
            if (peer.getSyncChainRequested() != null) {
                Deque<BlockId> blockIdWeGet = new LinkedList<>(msg.getBlockIds());
                if (blockIdWeGet.size() > 0) {
                    peer.setNeedSyncFromPeer(true); // peer와 node 간에 sync가 필요함을 의미함.
                }

                //check if the peer is a traitor
                if (!blockIdWeGet.isEmpty()) {
                    long num = blockIdWeGet.peek().getNum();
                    for (BlockId id : blockIdWeGet) {
                        if (id.getNum() != num++) {
                            // peer는 블록체인 정보를 전달할 때는 연결된 형태로 데이터를 전달해 줘야 함.
                            throw new TraitorPeerException("We get a not continuous block inv from " + peer);
                        }
                    }

                    // sync chain을 위한 요청에서 key값이 비어 있는 경우에는 generate block 정보가 들어있어야 한다.
                    if (peer.getSyncChainRequested().getKey().isEmpty()) {
                        if (blockIdWeGet.peek().getNum() != 1) {
                            throw new TraitorPeerException("We want a block inv starting from beginning from " + peer);
                        }
                    } else {
                        if (!peer.getSyncChainRequested().getKey().contains(blockIdWeGet.peek())) {
                            throw new TraitorPeerException(String.format(
                                    "We get a unlinked block chain from " + peer
                                            + "\n Our head is " + peer.getSyncChainRequested().getKey().getLast()
                                            .getString()
                                            + "\n Peer give us is " + blockIdWeGet.peek().getString()));
                        }
                    }

                    // CLOKC_MAX_DELAY를 통하여 시간 지연에 따른 보정을 진행했을 때 특정 블록 번호보다  크다면 전달받은 블록체인 정보는 문제가 있는 것으로 판단.
                    if (del.getHeadBlockId().getNum() > 0) {
                        long maxRemainTime = ChainConstant.CLOCK_MAX_DELAY + System.currentTimeMillis() - del.getBlockTime(del.getSolidBlockId());
                        long maxFutureNum = maxRemainTime / BLOCK_PRODUCED_INTERVAL + del.getSolidBlockId().getNum();
                        if (blockIdWeGet.peekLast().getNum() + msg.getRemainNum() > maxFutureNum) {
                            throw new TraitorPeerException(
                                    "Block num " + blockIdWeGet.peekLast().getNum() + "+" + msg.getRemainNum()
                                            + " is gt future max num " + maxFutureNum + " from " + peer
                                            + ", maybe the local clock is not right.");
                        }
                    }
                }

                if (msg.getRemainNum() == 0
                        && (blockIdWeGet.isEmpty() || (blockIdWeGet.size() == 1 && del.containBlock(blockIdWeGet.peek())))
                        && peer.getSyncBlockToFetch().isEmpty()
                        && peer.getUnfetchSyncNum() == 0) {
                    peer.setNeedSyncFromPeer(false);
                    unSyncNum = getUnSyncNum();
                    if (unSyncNum == 0) {
                        del.syncToCli(0);
                    }
                    peer.setSyncChainRequested(null);
                    return;
                }

                if (!blockIdWeGet.isEmpty() && peer.getSyncBlockToFetch().isEmpty()) {
                    boolean isFound = false;

                    for (PeerConnection peerToCheck : getActivePeer()) {
                        BlockId blockId = peerToCheck.getSyncBlockToFetch().peekFirst();
                        if (!peerToCheck.equals(peer) && blockId != null && blockId
                                .equals(blockIdWeGet.peekFirst())) {
                            isFound = true;
                            break;
                        }
                    }

                    if (!isFound) {
                        while (!blockIdWeGet.isEmpty() && del.containBlock(blockIdWeGet.peek())) {
                            updateBlockWeBothHave(peer, blockIdWeGet.peek());
                            blockIdWeGet.poll();
                        }
                    }
                } else if (!blockIdWeGet.isEmpty()) {
                    while (!peer.getSyncBlockToFetch().isEmpty()) {
                        if (!peer.getSyncBlockToFetch().peekLast().equals(blockIdWeGet.peekFirst())) {
                            peer.getSyncBlockToFetch().pollLast();
                        } else {
                            break;
                        }
                    }

                    if (peer.getSyncBlockToFetch().isEmpty() && del.containBlock(blockIdWeGet.peek())) {
                        updateBlockWeBothHave(peer, blockIdWeGet.peek());

                    }
                    blockIdWeGet.poll();
                }

                peer.setUnfetchSyncNum(msg.getRemainNum());
                peer.getSyncBlockToFetch().addAll(blockIdWeGet);
                synchronized (freshBlockId) {
                    while (!peer.getSyncBlockToFetch().isEmpty()
                            && freshBlockId.contains(peer.getSyncBlockToFetch().peek())) {
                        BlockId blockId = peer.getSyncBlockToFetch().pop();
                        updateBlockWeBothHave(peer, blockId);
                        logger.info("Block {} from {} is processed", blockId.getString(),
                                peer.getNode().getHost());
                    }
                }

                if (msg.getRemainNum() == 0 && peer.getSyncBlockToFetch().isEmpty()) {
                    peer.setNeedSyncFromPeer(false);
                }

                long newUnSyncNum = getUnSyncNum();
                if (unSyncNum != newUnSyncNum) {
                    unSyncNum = newUnSyncNum;
                    del.syncToCli(unSyncNum);
                }

                peer.setSyncChainRequested(null);

                if (msg.getRemainNum() == 0) {
                    if (!peer.getSyncBlockToFetch().isEmpty()) {
                        isFetchSyncActive = true;
                    } else {
                        //let peer know we are sync.
                        syncNextBatchChainIds(peer);
                    }
                } else {
                    if (peer.getSyncBlockToFetch().size() > NodeConstant.SYNC_FETCH_BATCH_NUM) {
                        isFetchSyncActive = true;
                    } else {
                        syncNextBatchChainIds(peer);
                    }
                }

            } else {
                throw new TraitorPeerException("We don't send sync request to " + peer);
            }

        } catch (TraitorPeerException e) {
            logger.error(e.getMessage());
            banTraitorPeer(peer, ReasonCode.BAD_PROTOCOL);
        } catch (StoreException e) {
            //we try update our both known block but this block is null
            //because when we try to switch to this block's fork then fail.
            //The reason only is we get a bad block which peer broadcast to us.
            logger.error(e.getMessage());
            banTraitorPeer(peer, ReasonCode.BAD_BLOCK);
        }
    }

    private void startFetchItem() {

    }

    /**
     *
     * @return
     */
    private long getUnSyncNum() {
        if (getActivePeer().isEmpty()) {
            return 0;
        }
        return getActivePeer().stream()
                .mapToLong(peer -> peer.getUnfetchSyncNum() + peer.getSyncBlockToFetch().size())
                .max()
                .getAsLong();
    }

    private synchronized void startFetchSyncBlock() {
        HashMap<PeerConnection, List<BlockId>> send = new HashMap<>();
        HashSet<BlockId> request = new HashSet<>();

        getActivePeer().stream()
                .filter(peer -> peer.isNeedSyncFromPeer() && !peer.isBusy())
                .forEach(peer -> {
                    if (!send.containsKey(peer)) {
                        send.put(peer, new LinkedList<>());
                    }
                    for (BlockId blockId : peer.getSyncBlockToFetch()) {
                        if (!request.contains(blockId)
                                && (syncBlockIdWeRequested.getIfPresent(blockId) == null)) {
                            send.get(peer).add(blockId);
                            request.add(blockId);
                            if (send.get(peer).size()
                                    > MAX_BLOCKS_SYNC_FROM_ONE_PEER) { //Max Blocks peer get one time
                                break;
                            }
                        }
                    }
                });

        send.forEach((peer, blockIds) -> {
            blockIds.forEach(blockId -> {
                syncBlockIdWeRequested.put(blockId, System.currentTimeMillis());
                peer.getSyncBlockRequested().put(blockId, System.currentTimeMillis());
            });
            List<Sha256Hash> ids = new LinkedList<>();
            ids.addAll(blockIds);
            if (!ids.isEmpty()) {
                peer.sendMessage(new FetchInvDataMessage(ids, InventoryType.BLOCK));
            }
        });

        send.clear();
    }

    private void updateBlockWeBothHave(PeerConnection peer, BlockCapsule block) {
        logger.info("update peer {} block both we have {}", peer.getNode().getHost(), block.getBlockId().getString());
        peer.setHeadBlockWeBothHave(block.getBlockId());
        peer.setHeadBlockTimeWeBothHave(block.getTimeStamp());
        peer.setLastBlockUpdateTime(System.currentTimeMillis());
    }

    private void updateBlockWeBothHave(PeerConnection peer, BlockId blockId) throws StoreException {
        logger.info("update peer {} block both we have, {}", peer.getNode().getHost(),
                blockId.getString());
        peer.setHeadBlockWeBothHave(blockId);
        long time = ((BlockMessage) del.getData(blockId, MessageTypes.BLOCK)).getBlockCapsule()
                .getTimeStamp();
        peer.setHeadBlockTimeWeBothHave(time);
        peer.setLastBlockUpdateTime(System.currentTimeMillis());
    }

    public Collection<PeerConnection> getActivePeer() {
        return pool.getActivePeers();
    }

    private void startSyncWithPeer(PeerConnection peer) {
        peer.setNeedSyncFromPeer(true);
        peer.getSyncBlockToFetch().clear();
        peer.setUnfetchSyncNum(0);
        updateBlockWeBothHave(peer, del.getGenesisBlock());
        peer.setBanned(false);
        syncNextBatchChainIds(peer);
    }

    /**
     * 다시 sync를 하기 위한 블록정보를 SyncBlockChainMessage에 담아서 peer에게 send 함.
     *
     * @param peer
     */
    private void syncNextBatchChainIds(PeerConnection peer) {
        synchronized (syncBlock) {
            if (peer.isDisconnect()) {
                logger.warn("Peer {} is disconnect", peer.getInetAddress());
                return;
            }
            if (peer.getSyncChainRequested() != null) {
                logger.info("Peer {} is in sync.", peer.getInetAddress());
                return;
            }
            try {
                Deque<BlockId> chainSummary =
                        del.getBlockChainSummary(peer.getHeadBlockWeBothHave(), peer.getSyncBlockToFetch());
                peer.setSyncChainRequested(new Pair<>(chainSummary, System.currentTimeMillis()));
                peer.sendMessage(new SyncBlockChainMessage((LinkedList<BlockId>) chainSummary));

            } catch (Brte2Exception e) {
                logger.error("Peer {} sync next batch chainIds failed, error: {}.",
                        peer.getNode().getHost(), e.getMessage());
                disconnectPeer(peer, ReasonCode.FORKED);
            }
        }
    }

    @Override
    public void onConnectPeer(PeerConnection peer) {
        if (peer.getHelloMessage().getHeadBlockId().getNum() > del.getHeadBlockId().getNum()) {
            peer.setBrte2State(Brte2State.SYNCING);
            startSyncWithPeer(peer);
        } else {
            peer.setBrte2State(Brte2State.SYNC_COMPLETED);
        }
    }

    @Override
    public void onDisconnectPeer(PeerConnection peer) {

        if (!peer.getSyncBlockRequested().isEmpty()) {
            peer.getSyncBlockRequested().keySet()
                    .forEach(blockId -> syncBlockIdWeRequested.invalidate(blockId));
            isFetchSyncActive = true;
        }

        if (!peer.getAdvObjWeRequested().isEmpty()) {
            peer.getAdvObjWeRequested().keySet().forEach(item -> {
                if (getActivePeer().stream()
                        .filter(peerConnection -> !peerConnection.equals(peer))
                        .filter(peerConnection -> peerConnection.getInvToUs().contains(item.getHash()))
                        .findFirst()
                        .isPresent()) {
                    advObjToFetch.put(item.getHash(), new PriorItem(item,
                            fetchSequenceCounter.incrementAndGet()));
                }
            });
        }
    }

    public void shutDown() {
        logExecutor.shutdown();
        trxsHandlePool.shutdown();
        disconnectInactiveExecutor.shutdown();
        cleanInventoryExecutor.shutdown();
        broadPool.shutdown();
        loopSyncBlockChain.shutdown();
        loopFetchBlocks.shutdown();
        loopAdvertiseInv.shutdown();
        fetchSyncBlocksExecutor.shutdown();
        handleSyncBlockExecutor.shutdown();
    }

    private void disconnectPeer(PeerConnection peer, ReasonCode reason) {
        peer.setSyncFlag(false);
        peer.disconnect(reason);
    }

    @Getter
    class PriorItem implements java.lang.Comparable<PriorItem> {

        private final long count;

        private final Item item;

        private long time;

        public PriorItem(Item item, long count) {
            this.item = item;
            this.count = count;
            this.time = Time.getCurrentMillis();
        }

        public Sha256Hash getHash() {
            return item.getHash();
        }

        public InventoryType getType() {
            return item.getType();
        }

        public void refreshTime() {
            this.time = Time.getCurrentMillis();
        }

        @Override
        public int compareTo(final PriorItem o) {
            if (!this.item.getType().equals(o.getItem().getType())) {
                return this.item.getType().equals(InventoryType.BLOCK) ? -1 : 1;
            }
            return Long.compare(this.count, o.getCount());
        }
    }

    class InvToSend {

        private final HashMap<PeerConnection, HashMap<InventoryType, LinkedList<Sha256Hash>>> send = new HashMap<>();

        public void clear() {
            this.send.clear();
        }

        public void add(Entry<Sha256Hash, InventoryType> id, PeerConnection peer) {
            if (send.containsKey(peer) && !send.get(peer).containsKey(id.getValue())) { // send 객체에 peer 정보를 보유하고 있으면서, 해당 peer에 특정 InventoryType이 존재하지 않는 경우
                send.get(peer).put(id.getValue(), new LinkedList<>());
            } else if (!send.containsKey(peer)) { //send 객체에 peer 정보가 존재하지 않는 경우
                send.put(peer, new HashMap<>());
                send.get(peer).put(id.getValue(), new LinkedList<>());
            }
            send.get(peer).get(id.getValue()).offer(id.getKey()); // send 객체의 peer의 InventoryType key에 해당하는 LinkedList에 SHA256Hash값을 추가함.
        }

        public void add(PriorItem id, PeerConnection peer) {
            if (send.containsKey(peer) && !send.get(peer).containsKey(id.getType())) {
                send.get(peer).put(id.getType(), new LinkedList<>());
            } else if (!send.containsKey(peer)) {
                send.put(peer, new HashMap<>());
                send.get(peer).put(id.getType(), new LinkedList<>());
            }
            send.get(peer).get(id.getType()).offer(id.getHash());
        }

        public int getSize(PeerConnection peer) {
            if (send.containsKey(peer)) {
                return send.get(peer).values().stream().mapToInt(LinkedList::size).sum();
            }

            return 0;
        }

        /**
         * send 객체에 저장된 정보를 Peer에게 sending 하는 메소드
         */
        void sendInv() {
            send.forEach((peer, ids) ->
                    ids.forEach((key, value) -> {
                        if (key.equals(InventoryType.BLOCK)) {
                            value.sort(Comparator.comparingLong(value1 -> new BlockId(value1).getNum())); // blockNo 순으로 정렬
                        }
                        peer.sendMessage(new InventoryMessage(value, key));
                    }));
        }

        void sendFetch() {
            send.forEach((peer, ids) ->
                    ids.forEach((key, value) -> {
                        if (key.equals(InventoryType.BLOCK)) {
                            value.sort(Comparator.comparingLong(value1 -> new BlockId(value1).getNum()));
                        }
                        peer.sendMessage(new FetchInvDataMessage(value, key));
                    }));
        }
    }

}

