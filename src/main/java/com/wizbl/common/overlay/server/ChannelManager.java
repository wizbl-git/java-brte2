package com.wizbl.common.overlay.server;

import com.wizbl.common.overlay.client.PeerClient;
import com.wizbl.common.overlay.discover.node.Node;
import com.wizbl.core.config.args.Args;
import com.wizbl.core.db.ByteArrayWrapper;
import com.wizbl.protos.Protocol.ReasonCode;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static com.wizbl.protos.Protocol.ReasonCode.*;

/**
 * ChannelManager 클래스는 Netty 에서 사용되는 channel 을 관리하기 위한 클래스로서 채널 생성 및 연결과 관련된 부분을 관리함. <br/>
 * 주로 Server의 channel을 관리하는 클래스임.
 */
@Component
public class ChannelManager {

  private static final Logger logger = LoggerFactory.getLogger("ChannelManager");

  private final Map<ByteArrayWrapper, Channel> activePeers = new ConcurrentHashMap<>();

  private Cache<InetAddress, ReasonCode> badPeers = CacheBuilder.newBuilder().maximumSize(10000)
      .expireAfterWrite(1, TimeUnit.HOURS).recordStats().build();

  private Cache<InetAddress, ReasonCode> recentlyDisconnected = CacheBuilder.newBuilder()
      .maximumSize(1000).expireAfterWrite(30, TimeUnit.SECONDS).recordStats().build();

  @Getter
  private Map<InetAddress, Node> trustPeers = new ConcurrentHashMap();

  private Args args = Args.getInstance();

  private int maxActivePeers = args.getNodeMaxActiveNodes();

  private int getMaxActivePeersWithSameIp = args.getNodeMaxActiveNodesWithSameIp();

  private PeerServer peerServer;

  private PeerClient peerClient;

  @Autowired
  private SyncPool syncPool;

  @Autowired
  private ChannelManager(final PeerServer peerServer, final PeerClient peerClient) {
    this.peerServer = peerServer;
    this.peerClient = peerClient;
  }

  /**
   * PeerServer 실행 및 PassiveNode 기준으로 trustPeer 항목을 저장
   */
  public void init() {
    if (this.args.getNodeListenPort() > 0) {
      new Thread(() -> peerServer.start(Args.getInstance().getNodeListenPort()),
          "PeerServerThread").start();
    }

    for (Node node : args.getPassiveNodes()) {
      trustPeers.put(new InetSocketAddress(node.getHost(), node.getPort()).getAddress(), node);
    }
    logger.info("Trust peer size {}", trustPeers.size());
  }

  public void processDisconnect(Channel channel, ReasonCode reason) {
    InetAddress inetAddress = channel.getInetAddress();
    if (inetAddress == null) {
      return;
    }
    switch (reason) {
      case BAD_PROTOCOL:
      case BAD_BLOCK:
      case BAD_TX:
        badPeers.put(channel.getInetAddress(), reason);
        break;
      default:
        recentlyDisconnected.put(channel.getInetAddress(), reason);
        break;
    }
  }

  public void notifyDisconnect(Channel channel) {
    syncPool.onDisconnect(channel);
    activePeers.values().remove(channel);
    if (channel != null) {
      if (channel.getNodeStatistics() != null) {
        channel.getNodeStatistics().notifyDisconnect();
      }
      InetAddress inetAddress = channel.getInetAddress();
      if (inetAddress != null && recentlyDisconnected.getIfPresent(inetAddress) == null) {
        recentlyDisconnected.put(channel.getInetAddress(), UNKNOWN);
      }
    }
  }

  public synchronized boolean processPeer(Channel peer) {

    if (!trustPeers.containsKey(peer.getInetAddress())) {
      if (recentlyDisconnected.getIfPresent(peer) != null) {
        logger.info("Peer {} recently disconnected.", peer.getInetAddress());
        return false;
      }

      if (badPeers.getIfPresent(peer) != null) {
        peer.disconnect(peer.getNodeStatistics().getDisconnectReason());
        return false;
      }

      if (!peer.isActive() && activePeers.size() >= maxActivePeers) {
        peer.disconnect(TOO_MANY_PEERS);
        return false;
      }

      if (getConnectionNum(peer.getInetAddress()) >= getMaxActivePeersWithSameIp) {
        peer.disconnect(TOO_MANY_PEERS_WITH_SAME_IP);
        return false;
      }
    }

    Channel channel = activePeers.get(peer.getNodeIdWrapper());
    if (channel != null) {
      if (channel.getStartTime() > peer.getStartTime()) {
        logger.info("Disconnect connection established later, {}", channel.getNode());
        channel.disconnect(DUPLICATE_PEER);
      } else {
        peer.disconnect(DUPLICATE_PEER);
        return false;
      }
    }
    activePeers.put(peer.getNodeIdWrapper(), peer);
    logger.info("Add active peer {}, total active peers: {}", peer, activePeers.size());
    return true;
  }

  public int getConnectionNum(InetAddress inetAddress) {
    int cnt = 0;
    for (Channel channel : activePeers.values()) {
      if (channel.getInetAddress().equals(inetAddress)) {
        cnt++;
      }
    }
    return cnt;
  }

  public Collection<Channel> getActivePeers() {
    return activePeers.values();
  }

  public Cache<InetAddress, ReasonCode> getRecentlyDisconnected() {
    return this.recentlyDisconnected;
  }

  public Cache<InetAddress, ReasonCode> getBadPeers() {
    return this.badPeers;
  }

  public void close() {
    peerServer.close();
    peerClient.close();
  }
}
