package com.wizbl.core.net.peer;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import com.wizbl.common.overlay.server.Channel;
import com.wizbl.common.overlay.server.MessageQueue;
import com.wizbl.core.net.message.Brte2Message;

@Component
@Scope("prototype")
public class Brte2Handler extends SimpleChannelInboundHandler<Brte2Message> {

  protected PeerConnection peer;

  private MessageQueue msgQueue = null;

  public PeerConnectionDelegate peerDel;

  public void setPeerDel(PeerConnectionDelegate peerDel) {
    this.peerDel = peerDel;
  }

  @Override
  public void channelRead0(final ChannelHandlerContext ctx, Brte2Message msg) throws Exception {
    msgQueue.receivedMessage(msg);
    peerDel.onMessage(peer, msg);
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    peer.processException(cause);
  }

  public void setMsgQueue(MessageQueue msgQueue) {
    this.msgQueue = msgQueue;
  }

  public void setChannel(Channel channel) {
    this.peer = (PeerConnection) channel;
  }

}