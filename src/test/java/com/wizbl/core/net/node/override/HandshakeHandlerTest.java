package com.wizbl.core.net.node.override;

import com.wizbl.common.overlay.discover.node.Node;
import com.wizbl.common.overlay.message.HelloMessage;
import com.wizbl.common.overlay.server.HandshakeHandler;
import io.netty.channel.ChannelHandlerContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

@Component
@Scope("prototype")
public class HandshakeHandlerTest extends HandshakeHandler {

  private Node node;

  public HandshakeHandlerTest() {
  }

  public HandshakeHandlerTest setNode(Node node) {
    this.node = node;
    return this;
  }

  @Override
  protected void sendHelloMsg(ChannelHandlerContext ctx, long time) {
    HelloMessage message = new HelloMessage(node, time,
            manager.getGenesisBlockId(), manager.getSolidBlockId(), manager.getHeadBlockId());
    ctx.writeAndFlush(message.getSendData());
    channel.getNodeStatistics().messageStatistics.addTcpOutMessage(message);
    logger.info("Handshake Send to {}, {}", ctx.channel().remoteAddress(), message);
  }

  public void close() {
    manager.closeAllStore();
  }
}
