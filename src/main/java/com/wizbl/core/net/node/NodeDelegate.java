package com.wizbl.core.net.node;

import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import com.wizbl.common.overlay.message.Message;
import com.wizbl.common.utils.Sha256Hash;
import com.wizbl.core.capsule.BlockCapsule;
import com.wizbl.core.capsule.BlockCapsule.BlockId;
import com.wizbl.core.capsule.TransactionCapsule;
import com.wizbl.core.exception.BadBlockException;
import com.wizbl.core.exception.BadTransactionException;
import com.wizbl.core.exception.NonCommonBlockException;
import com.wizbl.core.exception.StoreException;
import com.wizbl.core.exception.Brte2Exception;
import com.wizbl.core.exception.UnLinkedBlockException;
import com.wizbl.core.net.message.MessageTypes;

public interface NodeDelegate {

  LinkedList<Sha256Hash> handleBlock(BlockCapsule block, boolean syncMode)
      throws BadBlockException, UnLinkedBlockException, InterruptedException, NonCommonBlockException;

  boolean handleTransaction(TransactionCapsule trx) throws BadTransactionException;

  LinkedList<BlockId> getLostBlockIds(List<BlockId> blockChainSummary) throws StoreException;

  Deque<BlockId> getBlockChainSummary(BlockId beginBLockId, Deque<BlockId> blockIds)
      throws Brte2Exception;

  Message getData(Sha256Hash msgId, MessageTypes type) throws StoreException;

  void syncToCli(long unSyncNum);

  long getBlockTime(BlockId id);

  BlockId getHeadBlockId();

  BlockId getSolidBlockId();

  boolean contain(Sha256Hash hash, MessageTypes type);

  boolean containBlock(BlockId id);

  long getHeadBlockTimeStamp();

  boolean containBlockInMainChain(BlockId id);

  BlockCapsule getGenesisBlock();

  boolean canChainRevoke(long num);
}
