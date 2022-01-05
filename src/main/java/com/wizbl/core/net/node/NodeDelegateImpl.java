package com.wizbl.core.net.node;

import com.wizbl.common.overlay.message.Message;
import com.wizbl.common.utils.Sha256Hash;
import com.wizbl.core.capsule.BlockCapsule;
import com.wizbl.core.capsule.BlockCapsule.BlockId;
import com.wizbl.core.capsule.TransactionCapsule;
import com.wizbl.core.config.Parameter.NodeConstant;
import com.wizbl.core.db.Manager;
import com.wizbl.core.exception.*;
import com.wizbl.core.net.message.BlockMessage;
import com.wizbl.core.net.message.MessageTypes;
import com.wizbl.core.net.message.TransactionMessage;
import com.google.common.primitives.Longs;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

import static com.wizbl.core.config.Parameter.ChainConstant.BLOCK_PRODUCED_INTERVAL;
import static com.wizbl.core.config.Parameter.ChainConstant.BLOCK_SIZE;

/**
 * NodeDelegateImpl 클래스는 NodeImpl 클래스에서의 일부 기능을 위임받아서 실행하는 클래스로 보여짐.
 * (다만 어떠한 기준에서 이렇게 분리가 된 것인지는 확인할 필요가 있음.) <br/>
 */
@Slf4j
public class NodeDelegateImpl implements NodeDelegate {

    private Manager dbManager;

    /**
     * NodeDelegateImpl 객체 생성
     *
     * @param dbManager
     */
    public NodeDelegateImpl(Manager dbManager) {
        this.dbManager = dbManager;
    }

    /**
     * 블록 사이즈, 블록 생성 시간에 대한 검증 및 블록에 저장된 트랜잭션에 sign이 제대뢰 되었는지를 검증 함.
     * 이러한 검증이 마무리 되면 pushBlock을 호출하여 block 저장작업을 진행하며, syncMode == false이면 tx 정보를 반환함.
     *
     * @param block
     * @param syncMode
     * @return
     * @throws BadBlockException
     * @throws UnLinkedBlockException
     * @throws InterruptedException
     * @throws NonCommonBlockException
     */
    @Override
    public synchronized LinkedList<Sha256Hash> handleBlock(BlockCapsule block, boolean syncMode)
            throws BadBlockException, UnLinkedBlockException, InterruptedException, NonCommonBlockException {

        // block size 확인
        if (block.getInstance().getSerializedSize() > BLOCK_SIZE + 100) { //TODO - 왜 100인거지???
            throw new BadBlockException("block size over limit");
        }

        // 블록 생성 시간 확인
        long gap = block.getTimeStamp() - System.currentTimeMillis();
        if (gap >= BLOCK_PRODUCED_INTERVAL) {
            throw new BadBlockException("block time error");
        }

        try {
            // 일반적으로 transaction의 경우에는 Manager의 pushTransaction()에서 내부적으로 validateSign을 검증하지만
            // 여기서는 다른 노드로부터 전달받은 블록에 존재하는 transaction에 대한 검증을 진행하므로 위의 절차가 아닌 preValidateTransactionSign()을 실행함.
            dbManager.preValidateTransactionSign(block);
            dbManager.pushBlock(block);
            if (!syncMode) {
                List<TransactionCapsule> trx = null;
                trx = block.getTransactions();
                return trx.stream()
                        .map(TransactionCapsule::getTransactionId)
                        .collect(Collectors.toCollection(LinkedList::new));
            } else {
                return null;
            }

        } catch (AccountResourceInsufficientException e) {
            throw new BadBlockException("AccountResourceInsufficientException," + e.getMessage());
        } catch (ValidateScheduleException e) {
            throw new BadBlockException("validate schedule exception," + e.getMessage());
        } catch (ValidateSignatureException e) {
            throw new BadBlockException("validate signature exception," + e.getMessage());
        } catch (ContractValidateException e) {
            throw new BadBlockException("ContractValidate exception," + e.getMessage());
        } catch (ContractExeException e) {
            throw new BadBlockException("Contract Execute exception," + e.getMessage());
        } catch (TaposException e) {
            throw new BadBlockException("tapos exception," + e.getMessage());
        } catch (DupTransactionException e) {
            throw new BadBlockException("DupTransaction exception," + e.getMessage());
        } catch (TooBigTransactionException e) {
            throw new BadBlockException("TooBigTransaction exception," + e.getMessage());
        } catch (TooBigTransactionResultException e) {
            throw new BadBlockException("TooBigTransaction exception," + e.getMessage());
        } catch (TransactionExpirationException e) {
            throw new BadBlockException("Expiration exception," + e.getMessage());
        } catch (BadNumberBlockException e) {
            throw new BadBlockException("bad number exception," + e.getMessage());
        } catch (ReceiptCheckErrException e) {
            throw new BadBlockException("TransactionTrace Exception," + e.getMessage());
        } catch (VMIllegalException e) {
            throw new BadBlockException(e.getMessage());
        }

    }


    /**
     * TransactionIdCache에 trx의 transactionId가 존재하는지 여부를 검증한 후 TransactionIdCache에 해당 txId가 존재하지 않는다면,
     * trx를 pendingTransaction에 추가함. <br/>
     * pendingTransaction에 trx 추가가 정상적으로 종료되면 true 를 반환함.
     *
     * @param trx
     * @return
     * @throws BadTransactionException
     */
    @Override
    public boolean handleTransaction(TransactionCapsule trx) throws BadTransactionException {
        if (dbManager.getDynamicPropertiesStore().supportVM()) {
            trx.resetResult(); // result 초기화
        }

        logger.debug("handle transaction");
        if (dbManager.getTransactionIdCache().getIfPresent(trx.getTransactionId()) != null) {
            logger.warn("This transaction has been processed");
            return false;
        } else {
            dbManager.getTransactionIdCache().put(trx.getTransactionId(), true);
        }

        try {
            dbManager.pushTransaction(trx); // trx를 pendingTransaction에 추가하는 작업
        } catch (ContractSizeNotEqualToOneException
                | ValidateSignatureException
                | VMIllegalException e) {
            throw new BadTransactionException(e.getMessage());
        } catch (ContractValidateException
                | ContractExeException
                | AccountResourceInsufficientException
                | DupTransactionException
                | TaposException
                | TooBigTransactionException
                | TransactionExpirationException
                | ReceiptCheckErrException
                | TooBigTransactionResultException e) {
            logger.warn("Handle transaction {} failed, reason: {}", trx.getTransactionId(), e.getMessage());
            return false;
        }

        return true;
    }

    /**
     * unForkedBlockId는 mainChain에 존재하는 BlockId 가운데 첫번째 blockId를 의미함.
     * manager에서 관리하는 latestBlockHeaderNum과 unForkedBlockIdNum + NodeConstant.SYNC_FETCH_BATCH_NUM의 크기를 비교하여
     * 양자간에 더 작은 쪽의 블록 번호를 가지고
     * @param blockChainSummary
     * @return
     * @throws StoreException
     */
    @Override
    public LinkedList<BlockId> getLostBlockIds(List<BlockId> blockChainSummary) throws StoreException {

        if (dbManager.getHeadBlockNum() == 0) {
            return new LinkedList<>();
        }

        BlockId unForkedBlockId;

        if (blockChainSummary.isEmpty() ||
                (blockChainSummary.size() == 1
                        && blockChainSummary.get(0).equals(dbManager.getGenesisBlockId()))) {
            unForkedBlockId = dbManager.getGenesisBlockId();
        } else if (blockChainSummary.size() == 1
                && blockChainSummary.get(0).getNum() == 0) {
            return new LinkedList(Arrays.asList(dbManager.getGenesisBlockId()));
        } else {
            Collections.reverse(blockChainSummary);
            unForkedBlockId =
                    blockChainSummary
                            .stream()
                            .filter(blockId -> containBlockInMainChain(blockId))
                            .findFirst().orElse(null);
            if (unForkedBlockId == null) {
                return new LinkedList<>();
            }
        }

        long unForkedBlockIdNum = unForkedBlockId.getNum();
        // dbManager.getHeadBlockNum => getDynamicPropertiesStore().getLatestBlockHeaderNumber();
        long len = Longs
                .min(dbManager.getHeadBlockNum(), unForkedBlockIdNum + NodeConstant.SYNC_FETCH_BATCH_NUM);

        LinkedList<BlockId> blockIds = new LinkedList<>();
        for (long i = unForkedBlockIdNum; i <= len; i++) {
            BlockId id = dbManager.getBlockIdByNum(i);
            blockIds.add(id);
        }
        return blockIds;
    }

    /**
     * @param beginBlockId
     * @param blockIdsToFetch
     * @return
     * @throws Brte2Exception
     */
    @Override
    public Deque<BlockId> getBlockChainSummary(BlockId beginBlockId, Deque<BlockId> blockIdsToFetch)
            throws Brte2Exception {

        Deque<BlockId> retSummary = new LinkedList<>();
        List<BlockId> blockIds = new ArrayList<>(blockIdsToFetch);
        long highBlkNum;
        long highNoForkBlkNum;
        long syncBeginNumber = dbManager.getSyncBeginNumber();
        long lowBlkNum = syncBeginNumber < 0 ? 0 : syncBeginNumber;

        LinkedList<BlockId> forkList = new LinkedList<>();

        if (!beginBlockId.equals(getGenesisBlock().getBlockId())) {
            if (containBlockInMainChain(beginBlockId)) {
                highBlkNum = beginBlockId.getNum();
                if (highBlkNum == 0) {
                    throw new Brte2Exception(
                            "This block don't equal my genesis block hash, but it is in my DB, the block id is :"
                                    + beginBlockId.getString());
                }
                highNoForkBlkNum = highBlkNum;
                if (beginBlockId.getNum() < lowBlkNum) {
                    lowBlkNum = beginBlockId.getNum();
                }
            } else {
                forkList = dbManager.getBlockChainHashesOnFork(beginBlockId);
                if (forkList.isEmpty()) {
                    throw new UnLinkedBlockException(
                            "We want to find forkList of this block: " + beginBlockId.getString()
                                    + " ,but in KhasoDB we can not find it, It maybe a very old beginBlockId, we are sync once,"
                                    + " we switch and pop it after that time. ");
                }
                highNoForkBlkNum = forkList.peekLast().getNum();
                forkList.pollLast();
                Collections.reverse(forkList);
                highBlkNum = highNoForkBlkNum + forkList.size();
                if (highNoForkBlkNum < lowBlkNum) {
                    throw new UnLinkedBlockException(
                            "It is a too old block that we take it as a forked block long long ago"
                                    + "\n lowBlkNum:" + lowBlkNum
                                    + "\n highNoForkBlkNum" + highNoForkBlkNum);
                }
            }
        } else {
            highBlkNum = dbManager.getHeadBlockNum();
            highNoForkBlkNum = highBlkNum;

        }

        if (!blockIds.isEmpty() && highBlkNum != blockIds.get(0).getNum() - 1) {
            logger.error("Check ERROR: highBlkNum:" + highBlkNum + ",blockIdToSyncFirstNum is "
                    + blockIds.get(0).getNum() + ",blockIdToSyncEnd is " + blockIds.get(blockIds.size() - 1)
                    .getNum());
        }

        long realHighBlkNum = highBlkNum + blockIds.size();
        do {
            if (lowBlkNum <= highNoForkBlkNum) {
                retSummary.offer(dbManager.getBlockIdByNum(lowBlkNum));
            } else if (lowBlkNum <= highBlkNum) {
                retSummary.offer(forkList.get((int) (lowBlkNum - highNoForkBlkNum - 1)));
            } else {
                retSummary.offer(blockIds.get((int) (lowBlkNum - highBlkNum - 1)));
            }
            lowBlkNum += (realHighBlkNum - lowBlkNum + 2) / 2;
        } while (lowBlkNum <= realHighBlkNum);

        return retSummary;
    }

    @Override
    public Message getData(Sha256Hash hash, MessageTypes type)
            throws StoreException {
        switch (type) {
            case BLOCK:
                return new BlockMessage(dbManager.getBlockById(hash));
            case TRX:
                TransactionCapsule tx = dbManager.getTransactionStore().get(hash.getBytes());
                if (tx != null) {
                    return new TransactionMessage(tx.getData());
                }
                throw new ItemNotFoundException("transaction is not found");
            default:
                throw new BadItemException("message type not block or trx.");
        }
    }

    @Override
    public void syncToCli(long unSyncNum) {
        logger.info("There are " + unSyncNum + " blocks we need to sync.");
        if (unSyncNum == 0) {
            logger.info("Sync Block Completed !!!");
        }
        dbManager.setSyncMode(unSyncNum == 0);
    }

    @Override
    public long getBlockTime(BlockId id) {
        try {
            return dbManager.getBlockById(id).getTimeStamp();
        } catch (BadItemException e) {
            return dbManager.getGenesisBlock().getTimeStamp();
        } catch (ItemNotFoundException e) {
            return dbManager.getGenesisBlock().getTimeStamp();
        }
    }

    @Override
    public BlockId getHeadBlockId() {
        return dbManager.getHeadBlockId();
    }

    @Override
    public BlockId getSolidBlockId() {
        return dbManager.getSolidBlockId();
    }

    @Override
    public long getHeadBlockTimeStamp() {
        return dbManager.getHeadBlockTimeStamp();
    }

    @Override
    public boolean containBlock(BlockId id) {
        return dbManager.containBlock(id);
    }

    @Override
    public boolean containBlockInMainChain(BlockId id) {
        return dbManager.containBlockInMainChain(id);
    }

    @Override
    public boolean contain(Sha256Hash hash, MessageTypes type) {
        if (type.equals(MessageTypes.BLOCK)) {
            return dbManager.containBlock(hash);
        } else if (type.equals(MessageTypes.TRX)) {
            return dbManager.getTransactionStore().has(hash.getBytes());
        }
        return false;
    }

    @Override
    public BlockCapsule getGenesisBlock() {
        return dbManager.getGenesisBlock();
    }

    @Override
    public boolean canChainRevoke(long num) {
        return num >= dbManager.getSyncBeginNumber();
    }
}
