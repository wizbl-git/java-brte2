package com.wizbl.common.overlay.discover.node.statistics;

import lombok.extern.slf4j.Slf4j;
import com.wizbl.common.net.udp.message.UdpMessageTypeEnum;
import com.wizbl.common.overlay.message.Message;
import com.wizbl.core.net.message.FetchInvDataMessage;
import com.wizbl.core.net.message.InventoryMessage;
import com.wizbl.core.net.message.MessageTypes;
import com.wizbl.core.net.message.TransactionsMessage;

@Slf4j
public class MessageStatistics {

  //udp discovery
  public final MessageCount discoverInPing = new MessageCount();
  public final MessageCount discoverOutPing = new MessageCount();
  public final MessageCount discoverInPong = new MessageCount();
  public final MessageCount discoverOutPong = new MessageCount();
  public final MessageCount discoverInFindNode = new MessageCount();
  public final MessageCount discoverOutFindNode = new MessageCount();
  public final MessageCount discoverInNeighbours = new MessageCount();
  public final MessageCount discoverOutNeighbours = new MessageCount();

  //tcp p2p
  public final MessageCount p2pInHello = new MessageCount();
  public final MessageCount p2pOutHello = new MessageCount();
  public final MessageCount p2pInPing = new MessageCount();
  public final MessageCount p2pOutPing = new MessageCount();
  public final MessageCount p2pInPong = new MessageCount();
  public final MessageCount p2pOutPong = new MessageCount();
  public final MessageCount p2pInDisconnect = new MessageCount();
  public final MessageCount p2pOutDisconnect = new MessageCount();

  //tcp brte2
  public final MessageCount brte2InMessage = new MessageCount();
  public final MessageCount brte2OutMessage = new MessageCount();

  public final MessageCount brte2InSyncBlockChain = new MessageCount();
  public final MessageCount brte2OutSyncBlockChain = new MessageCount();
  public final MessageCount brte2InBlockChainInventory = new MessageCount();
  public final MessageCount brte2OutBlockChainInventory = new MessageCount();

  public final MessageCount brte2InTrxInventory = new MessageCount();
  public final MessageCount brte2OutTrxInventory = new MessageCount();
  public final MessageCount brte2InTrxInventoryElement = new MessageCount();
  public final MessageCount brte2OutTrxInventoryElement = new MessageCount();

  public final MessageCount brte2InBlockInventory = new MessageCount();
  public final MessageCount brte2OutBlockInventory = new MessageCount();
  public final MessageCount brte2InBlockInventoryElement = new MessageCount();
  public final MessageCount brte2OutBlockInventoryElement = new MessageCount();

  public final MessageCount brte2InTrxFetchInvData = new MessageCount();
  public final MessageCount brte2OutTrxFetchInvData = new MessageCount();
  public final MessageCount brte2InTrxFetchInvDataElement = new MessageCount();
  public final MessageCount brte2OutTrxFetchInvDataElement = new MessageCount();

  public final MessageCount brte2InBlockFetchInvData = new MessageCount();
  public final MessageCount brte2OutBlockFetchInvData = new MessageCount();
  public final MessageCount brte2InBlockFetchInvDataElement = new MessageCount();
  public final MessageCount brte2OutBlockFetchInvDataElement = new MessageCount();


  public final MessageCount brte2InTrx = new MessageCount();
  public final MessageCount brte2OutTrx = new MessageCount();
  public final MessageCount brte2InTrxs = new MessageCount();
  public final MessageCount brte2OutTrxs = new MessageCount();
  public final MessageCount brte2InBlock = new MessageCount();
  public final MessageCount brte2OutBlock = new MessageCount();
  public final MessageCount brte2OutAdvBlock = new MessageCount();

  public void addUdpInMessage(UdpMessageTypeEnum type) {
    addUdpMessage(type, true);
  }

  public void addUdpOutMessage(UdpMessageTypeEnum type) {
    addUdpMessage(type, false);
  }

  public void addTcpInMessage(Message msg) {
    addTcpMessage(msg, true);
  }

  public void addTcpOutMessage(Message msg) {
    addTcpMessage(msg, false);
  }

  private void addUdpMessage(UdpMessageTypeEnum type, boolean flag) {
    switch (type) {
      case DISCOVER_PING:
        if (flag) {
          discoverInPing.add();
        } else {
          discoverOutPing.add();
        }
        break;
      case DISCOVER_PONG:
        if (flag) {
          discoverInPong.add();
        } else {
          discoverOutPong.add();
        }
        break;
      case DISCOVER_FIND_NODE:
        if (flag) {
          discoverInFindNode.add();
        } else {
          discoverOutFindNode.add();
        }
        break;
      case DISCOVER_NEIGHBORS:
        if (flag) {
          discoverInNeighbours.add();
        } else {
          discoverOutNeighbours.add();
        }
        break;
      default:
        break;
    }
  }

  private void addTcpMessage(Message msg, boolean flag) {

    if (flag) {
      brte2InMessage.add();
    } else {
      brte2OutMessage.add();
    }

    switch (msg.getType()) {
      case P2P_HELLO:
        if (flag) {
          p2pInHello.add();
        } else {
          p2pOutHello.add();
        }
        break;
      case P2P_PING:
        if (flag) {
          p2pInPing.add();
        } else {
          p2pOutPing.add();
        }
        break;
      case P2P_PONG:
        if (flag) {
          p2pInPong.add();
        } else {
          p2pOutPong.add();
        }
        break;
      case P2P_DISCONNECT:
        if (flag) {
          p2pInDisconnect.add();
        } else {
          p2pOutDisconnect.add();
        }
        break;
      case SYNC_BLOCK_CHAIN:
        if (flag) {
          brte2InSyncBlockChain.add();
        } else {
          brte2OutSyncBlockChain.add();
        }
        break;
      case BLOCK_CHAIN_INVENTORY:
        if (flag) {
          brte2InBlockChainInventory.add();
        } else {
          brte2OutBlockChainInventory.add();
        }
        break;
      case INVENTORY:
        InventoryMessage inventoryMessage = (InventoryMessage) msg;
        int inventorySize = inventoryMessage.getInventory().getIdsCount();
        if (flag) {
          if (inventoryMessage.getInvMessageType() == MessageTypes.TRX) {
            brte2InTrxInventory.add();
            brte2InTrxInventoryElement.add(inventorySize);
          } else {
            brte2InBlockInventory.add();
            brte2InBlockInventoryElement.add(inventorySize);
          }
        } else {
          if (inventoryMessage.getInvMessageType() == MessageTypes.TRX) {
            brte2OutTrxInventory.add();
            brte2OutTrxInventoryElement.add(inventorySize);
          } else {
            brte2OutBlockInventory.add();
            brte2OutBlockInventoryElement.add(inventorySize);
          }
        }
        break;
      case FETCH_INV_DATA:
        FetchInvDataMessage fetchInvDataMessage = (FetchInvDataMessage) msg;
        int fetchSize = fetchInvDataMessage.getInventory().getIdsCount();
        if (flag) {
          if (fetchInvDataMessage.getInvMessageType() == MessageTypes.TRX) {
            brte2InTrxFetchInvData.add();
            brte2InTrxFetchInvDataElement.add(fetchSize);
          } else {
            brte2InBlockFetchInvData.add();
            brte2InBlockFetchInvDataElement.add(fetchSize);
          }
        } else {
          if (fetchInvDataMessage.getInvMessageType() == MessageTypes.TRX) {
            brte2OutTrxFetchInvData.add();
            brte2OutTrxFetchInvDataElement.add(fetchSize);
          } else {
            brte2OutBlockFetchInvData.add();
            brte2OutBlockFetchInvDataElement.add(fetchSize);
          }
        }
        break;
      case TRXS:
        TransactionsMessage transactionsMessage = (TransactionsMessage) msg;
        if (flag) {
          brte2InTrxs.add();
          brte2InTrx.add(transactionsMessage.getTransactions().getTransactionsCount());
        } else {
          brte2OutTrxs.add();
          brte2OutTrx.add(transactionsMessage.getTransactions().getTransactionsCount());
        }
        break;
      case TRX:
        if (flag) {
          brte2InMessage.add();
        } else {
          brte2OutMessage.add();
        }
        break;
      case BLOCK:
        if (flag) {
          brte2InBlock.add();
        }
        brte2OutBlock.add();
        break;
      default:
        break;
    }
  }

}
