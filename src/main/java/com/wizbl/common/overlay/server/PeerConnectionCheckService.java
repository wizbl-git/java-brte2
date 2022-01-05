package com.wizbl.common.overlay.server;

import com.wizbl.common.overlay.discover.node.statistics.NodeStatistics;
import com.wizbl.common.utils.CollectionUtils;
import com.wizbl.core.config.args.Args;
import com.wizbl.core.db.Manager;
import com.wizbl.core.net.peer.PeerConnection;
import com.wizbl.protos.Protocol.ReasonCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class PeerConnectionCheckService {

  public static final long CHECK_TIME = 5 * 60 * 1000L;
  private double disconnectNumberFactor = Args.getInstance().getDisconnectNumberFactor();
  private double maxConnectNumberFactor = Args.getInstance().getMaxConnectNumberFactor();

  @Autowired
  private SyncPool pool;

  @Autowired
  private ChannelManager channelManager;

  @Autowired
  private Manager manager;

  private ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(2,
      r -> new Thread(r, "check-peer-connect"));

  // dependency injection이 끝난 후에 초기화를 위해서 실행되는 method를 지정하는 annotation
  @PostConstruct
  public void check() {
    logger.info("start the PeerConnectionCheckService");
    scheduledExecutorService.scheduleWithFixedDelay(new CheckDataTransferTask(), 5, 5, TimeUnit.MINUTES);
    if (Args.getInstance().isOpenFullTcpDisconnect()) {
      scheduledExecutorService.scheduleWithFixedDelay(new CheckConnectNumberTask(), 4, 1, TimeUnit.MINUTES);
    }
  }

  @PreDestroy
  public void destroy() {
    scheduledExecutorService.shutdown();
  }

  /**
   * 노드에서 관리중인 active peer에 대해서 특정 조건을 만족하는 peer를 willDisconnectPeerList에 저장한 후 해당 peerList에 저장된 peer에 대해서
   * disconnect하는 메소드
   */
  private class CheckDataTransferTask implements Runnable {

    @Override
    public void run() {
      List<PeerConnection> peerConnectionList = pool.getActivePeers();
      List<PeerConnection> willDisconnectPeerList = new ArrayList<>();
      for (PeerConnection peerConnection : peerConnectionList) {
        NodeStatistics nodeStatistics = peerConnection.getNodeStatistics();

        if (!nodeStatistics.nodeIsHaveDataTransfer()  // 노드에게 데이터를 전송한 내용이 없고,
            && System.currentTimeMillis() - peerConnection.getStartTime() >= CHECK_TIME // CHECK_TIME 동안 연결한 내용이 없으며,
            && !peerConnection.isTrustPeer()  // 신뢰할 수 있는 peer(trustPeer)가 아닌 경우에
            && !nodeStatistics.isPredefined()) { // 노드의 통계정보가 미리 정해져 있지 않다면
          //if xxx minutes not have data transfer,disconnect the peer,exclude trust peer and active peer
          willDisconnectPeerList.add(peerConnection);
        }
        nodeStatistics.resetTcpFlow();
      }
      if (!willDisconnectPeerList.isEmpty() && peerConnectionList.size()
          > Args.getInstance().getNodeMaxActiveNodes() * maxConnectNumberFactor) {
        Collections.shuffle(willDisconnectPeerList);
        for (int i = 0; i < willDisconnectPeerList.size() * disconnectNumberFactor; i++) {
          logger.error("{} not have data transfer, disconnect the peer", willDisconnectPeerList.get(i).getInetAddress());
          willDisconnectPeerList.get(i).disconnect(ReasonCode.TOO_MANY_PEERS);
        }
      }
    }
  }

  /**
   * 노드에서 관리되고 있는 ActivePeer의 숫자가 MaxActiveNodes의 숫자보다 큰 경우에
   * 아래의 조건에 해당되는 노드에 대해서 평판(reputation)순으로 정렬한 후 disconnect 함.
   */
  private class CheckConnectNumberTask implements Runnable {

    @Override
    public void run() {
      if (pool.getActivePeers().size() >= Args.getInstance().getNodeMaxActiveNodes()) {
        logger.warn("connection pool is full");
        List<PeerConnection> peerList = new ArrayList<>();
        for (PeerConnection peer : pool.getActivePeers()) {
          if (!peer.isTrustPeer() && !peer.getNodeStatistics().isPredefined()) {
            peerList.add(peer);
          }
        }
        if (peerList.size() >= 2) {
          // 평판(reputation)에 의해서 정렬한 후 random하게 peer를 truncate 함.
          peerList.sort(Comparator.comparingInt((PeerConnection o) -> o.getNodeStatistics().getReputation()));
          peerList = CollectionUtils.truncateRandom(peerList, 2, 1);
        }
        for (PeerConnection peerConnection : peerList) {
          logger.warn("connection pool is full, disconnect the peer : {}",
              peerConnection.getInetAddress());
          peerConnection.disconnect(ReasonCode.RESET);
        }
      }
    }
  }

}