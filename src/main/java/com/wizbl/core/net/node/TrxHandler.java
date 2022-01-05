package com.wizbl.core.net.node;

import com.wizbl.core.config.args.Args;
import com.wizbl.core.net.message.TransactionMessage;
import com.wizbl.core.net.message.TransactionsMessage;
import com.wizbl.core.net.peer.PeerConnection;
import com.wizbl.protos.Protocol.Inventory.InventoryType;
import com.wizbl.protos.Protocol.ReasonCode;
import com.wizbl.protos.Protocol.Transaction;
import com.wizbl.protos.Protocol.Transaction.Contract.ContractType;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;

@Slf4j
@Component
public class TrxHandler {

  private NodeImpl nodeImpl;

  private static final int MAX_TRX_SIZE = 50_000;

  private static final int MAX_SMART_CONTRACT_SUBMIT_SIZE = 100;

  private static final int TIME_OUT = 10 * 60 * 1000;

  private final BlockingQueue<TrxEvent> smartContractQueue = new LinkedBlockingQueue(MAX_TRX_SIZE);

  private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue();

  private final int threadNum = Args.getInstance().getValidateSignThreadNum();
  private final ExecutorService trxHandlePool = new ThreadPoolExecutor(threadNum, threadNum, 0L,
          TimeUnit.MILLISECONDS, queue);

  private final ScheduledExecutorService smartContractExecutor = Executors.newSingleThreadScheduledExecutor();

  /**
   * TrxHandler 초기화
   *
   * @param nodeImpl
   */
  public void init(NodeImpl nodeImpl) {
    this.nodeImpl = nodeImpl;
    handleSmartContract();
  }

  /**
   * onHandleTransactionMessage를 처리하기 위한 trxHandPool을 주기적으로 실행함.
   */
  private void handleSmartContract() {
    smartContractExecutor.scheduleWithFixedDelay(() -> {
      try {
        while (queue.size() < MAX_SMART_CONTRACT_SUBMIT_SIZE) {
          TrxEvent event = smartContractQueue.take();
          if (System.currentTimeMillis() - event.getTime() > TIME_OUT) {
            logger.warn("Drop smart contract {} from peer {}.");
            continue;
          }
          trxHandlePool.submit(() -> nodeImpl.onHandleTransactionMessage(event.getPeer(), event.getMsg()));
        }
      } catch (Exception e) {
        logger.error("Handle smart contract exception", e);
      }
    }, 1000, 20, TimeUnit.MILLISECONDS);
  }

  /**
   * peer로부터 전달받은 Transaction 관련 메시지를 처리하는 메소드 <br/>
   * requested한 작업인 경우에만 해당 transaction을 처리하고 있음. <br/>
   * transaction type에 따라서 TriggerSmartContract or CreateSmartContract인 경우에는 smartContractQueue에 transaction을 저장하고,
   * 나머지 transaction의 경우에는 onHandleTransactionMessage에서 해당 transaction을 처리함.
   * @param peer
   * @param msg
   */
  public void handleTransactionsMessage(PeerConnection peer, TransactionsMessage msg) {
    for (Transaction trx : msg.getTransactions().getTransactionsList()) {
      Item item = new Item(new TransactionMessage(trx).getMessageId(), InventoryType.TRX);
      if (!peer.getAdvObjWeRequested().containsKey(item)) {
        logger.warn("Receive trx {} from peer {} without fetch request.", msg.getMessageId(), peer.getInetAddress());
        peer.setSyncFlag(false);
        peer.disconnect(ReasonCode.BAD_PROTOCOL);
        return;
      }
      peer.getAdvObjWeRequested().remove(item); // item 요청 내역 삭제
      int type = trx.getRawData().getContract(0).getType().getNumber();
      if (type == ContractType.TriggerSmartContract_VALUE || type == ContractType.CreateSmartContract_VALUE) {
        if (!smartContractQueue.offer(new TrxEvent(peer, new TransactionMessage(trx)))) {
          logger.warn("Add smart contract failed, smartContractQueue size {} queueSize {}", smartContractQueue.size(), queue.size());
        }
      } else {
        trxHandlePool.submit(() -> nodeImpl.onHandleTransactionMessage(peer, new TransactionMessage(trx)));
      }
    }
  }

  public boolean isBusy() {
    return queue.size() + smartContractQueue.size() > MAX_TRX_SIZE;
  }

  class TrxEvent {
    @Getter
    private final PeerConnection peer;
    @Getter
    private final TransactionMessage msg;
    @Getter
    private final long time;

    public TrxEvent(PeerConnection peer, TransactionMessage msg) {
      this.peer = peer;
      this.msg = msg;
      this.time = System.currentTimeMillis();
    }
  }
}