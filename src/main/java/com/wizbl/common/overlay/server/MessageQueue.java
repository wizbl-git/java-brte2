package com.wizbl.common.overlay.server;

import com.wizbl.common.overlay.message.Message;
import com.wizbl.common.overlay.message.PingMessage;
import com.wizbl.common.overlay.message.PongMessage;
import com.wizbl.core.net.message.InventoryMessage;
import com.wizbl.core.net.message.TransactionsMessage;
import com.wizbl.protos.Protocol.Inventory.InventoryType;
import com.wizbl.protos.Protocol.ReasonCode;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.Queue;
import java.util.concurrent.*;

@Component
@Scope("prototype")
public class MessageQueue {

  private static final Logger logger = LoggerFactory.getLogger("MessageQueue");

  private volatile boolean sendMsgFlag = false;

  private volatile long sendTime;

  private Thread sendMsgThread;

  private Channel channel;

  private ChannelHandlerContext ctx = null;

  private Queue<MessageRoundtrip> requestQueue = new ConcurrentLinkedQueue<>();

  // msgQueue는 sendMessage()메소드에 의해서 msgQueue에 데이터가 저장이 됨.
  // msgQueue는 HandshakeHandler의 handshaking이 정상적으로 종료되면, activate() 호출이 이뤄지면서 별도의 쓰레드에 의해서 msgQueue에 저장된
  // msg 정보들이 외부로 전달됨.
  private BlockingQueue<Message> msgQueue = new LinkedBlockingQueue<>();

  private static ScheduledExecutorService sendTimer = Executors.
      newSingleThreadScheduledExecutor(r -> new Thread(r, "sendTimer"));

  private ScheduledFuture<?> sendTask;


  /**
   * sendMsgThread에 의해서 msgQueue에 저장된 msg가 외부로 전파됨.
   * @param ctx
   */
  public void activate(ChannelHandlerContext ctx) {

    this.ctx = ctx;

    sendMsgFlag = true;

    sendTask = sendTimer.scheduleAtFixedRate(() -> {
      try {
        if (sendMsgFlag) {
          send();
        }
      } catch (Exception e) {
        logger.error("Unhandled exception", e);
      }
    }, 10, 10, TimeUnit.MILLISECONDS);

    sendMsgThread = new Thread(() -> {
      while (sendMsgFlag) {
        try {
          if (msgQueue.isEmpty()) {
            Thread.sleep(10);
            continue;
          }
          Message msg = msgQueue.take();
          ctx.writeAndFlush(msg.getSendData()).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
              logger.error("Fail send to {}, {}", ctx.channel().remoteAddress(), msg);
            }
          });
        } catch (Exception e) {
          logger.error("Fail send to {}, error info: {}", ctx.channel().remoteAddress(),
              e.getMessage());
        }
      }
    });
    sendMsgThread.setName("sendMsgThread-" + ctx.channel().remoteAddress());
    sendMsgThread.start();
  }

  public void setChannel(Channel channel) {
    this.channel = channel;
  }

  /**
   * msg를 send 하는 메소드. <br/>
   * @param msg
   * @return
   */
  public boolean sendMessage(Message msg) {
    if (msg instanceof PingMessage && sendTime > System.currentTimeMillis() - 10_000) {
      return false;
    }
    if (needToLog(msg)) {
      logger.info("Send to {}, {} ", ctx.channel().remoteAddress(), msg);
    }
    channel.getNodeStatistics().messageStatistics.addTcpOutMessage(msg);
    sendTime = System.currentTimeMillis();
    if (msg.getAnswerMessage() != null) { // PingMessage, SyncBlockchainMessage의 getAnswerMessage()만이 null을 반환하지 않음.
      requestQueue.add(new MessageRoundtrip(msg));
    } else {
      msgQueue.offer(msg);    // msg를 msgQueue의 끝에 add 함.
    }
    return true;
  }

  public void receivedMessage(Message msg) {
    if (needToLog(msg)) {
      logger.info("Receive from {}, {}", ctx.channel().remoteAddress(), msg);
    }
    channel.getNodeStatistics().messageStatistics.addTcpInMessage(msg);
    MessageRoundtrip messageRoundtrip = requestQueue.peek();
    if (messageRoundtrip != null && messageRoundtrip.getMsg().getAnswerMessage() == msg
        .getClass()) {
      requestQueue.remove();
    }
  }

  public void close() {
    sendMsgFlag = false;
    if (sendTask != null && !sendTask.isCancelled()) {
      sendTask.cancel(false);
      sendTask = null;
    }
    if (sendMsgThread != null) {
      try {
        sendMsgThread.join(20);
        sendMsgThread = null;
      } catch (Exception e) {
        logger.warn("Join send thread failed, peer {}", ctx.channel().remoteAddress());
      }
    }
  }

  private boolean needToLog(Message msg) {
    if (msg instanceof PingMessage ||
        msg instanceof PongMessage ||
        msg instanceof TransactionsMessage){
      return false;
    }

    if (msg instanceof InventoryMessage) {
      if (((InventoryMessage) msg).getInventoryType().equals(InventoryType.TRX)){
        return false;
      }
    }

    return true;
  }

  private void send() {
    MessageRoundtrip messageRoundtrip = requestQueue.peek();
    if (!sendMsgFlag || messageRoundtrip == null) {
      return;
    }
    if (messageRoundtrip.getRetryTimes() > 0 && !messageRoundtrip.hasToRetry()) {
      return;
    }
    if (messageRoundtrip.getRetryTimes() > 0) {
      channel.getNodeStatistics().nodeDisconnectedLocal(ReasonCode.PING_TIMEOUT);
      logger
          .warn("Wait {} timeout. close channel {}.", messageRoundtrip.getMsg().getAnswerMessage(),
              ctx.channel().remoteAddress());
      channel.close();
      return;
    }

    Message msg = messageRoundtrip.getMsg();

    ctx.writeAndFlush(msg.getSendData()).addListener((ChannelFutureListener) future -> {
      if (!future.isSuccess()) {
        logger.error("Fail send to {}, {}", ctx.channel().remoteAddress(), msg);
      }
    });

    messageRoundtrip.incRetryTimes();
    messageRoundtrip.saveTime();
  }

}
