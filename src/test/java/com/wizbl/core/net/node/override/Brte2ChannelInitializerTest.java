package com.wizbl.core.net.node.override;

import com.wizbl.common.overlay.server.Channel;
import com.wizbl.common.overlay.server.ChannelManager;
import com.wizbl.core.net.node.NodeImpl;
import com.wizbl.core.net.peer.PeerConnection;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

@Component
@Scope("prototype")
public class Brte2ChannelInitializerTest extends ChannelInitializer<NioSocketChannel> {

  private static final Logger logger = LoggerFactory.getLogger("Brte2ChannelInitializer");
  private final String remoteId;
  @Autowired
  ChannelManager channelManager;
  @Autowired
  private ApplicationContext ctx;
  private NodeImpl p2pNode;
  private boolean peerDiscoveryMode = false;

  private Channel channel;

  public Brte2ChannelInitializerTest(String remoteId) {
    this.remoteId = remoteId;
  }

  public void prepare() {
    channel = ctx.getBean(PeerConnection.class);
  }

  @Override
  public void initChannel(NioSocketChannel ch) throws Exception {
    try {
      channel.init(ch.pipeline(), remoteId, peerDiscoveryMode, channelManager, p2pNode);

      // limit the size of receiving buffer to 1024
      ch.config().setRecvByteBufAllocator(new FixedRecvByteBufAllocator(256 * 1024));
      ch.config().setOption(ChannelOption.SO_RCVBUF, 256 * 1024);
      ch.config().setOption(ChannelOption.SO_BACKLOG, 1024);

      // be aware of channel closing
      ch.closeFuture().addListener((ChannelFutureListener) future -> {
        logger.info("Close channel:" + channel);
        if (!peerDiscoveryMode) {
          channelManager.notifyDisconnect(channel);
        }
      });

    } catch (Exception e) {
      logger.error("Unexpected error: ", e);
    }
  }

  private boolean isInbound() {
    return remoteId == null || remoteId.isEmpty();
  }

  public void setPeerDiscoveryMode(boolean peerDiscoveryMode) {
    this.peerDiscoveryMode = peerDiscoveryMode;
  }

  public void setNodeImpl(NodeImpl p2pNode) {
    this.p2pNode = p2pNode;
  }

  public void close() {
    channelManager.close();
    channel.close();
  }
}
