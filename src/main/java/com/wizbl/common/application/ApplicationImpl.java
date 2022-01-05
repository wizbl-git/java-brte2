package com.wizbl.common.application;

import com.wizbl.core.config.args.Args;
import com.wizbl.core.db.BlockStore;
import com.wizbl.core.db.Manager;
import com.wizbl.core.net.node.Node;
import com.wizbl.core.net.node.NodeDelegate;
import com.wizbl.core.net.node.NodeDelegateImpl;
import com.wizbl.core.net.node.NodeImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * ApplicationImpl 클래스에서는 Application에 포함된 서비스 등에 대한 추가, 초기화, 실행, 정지 등을 관리함. <br/>
 * 또한 Node간의
 */
@Slf4j
@Component
public class ApplicationImpl implements Application {

  @Autowired
  private NodeImpl p2pNode;

  private BlockStore blockStoreDb;
  private ServiceContainer services;
  private NodeDelegate nodeDelegate;

  @Autowired
  private Manager dbManager;

  private boolean isProducer;


  private void resetP2PNode() {
    p2pNode.listen();
    p2pNode.syncFrom(null);
  }

  @Override
  public void setOptions(Args args) {
    // not used
  }

  @Override
  @Autowired
  public void init(Args args) {
    blockStoreDb = dbManager.getBlockStore();
    services = new ServiceContainer();
    nodeDelegate = new NodeDelegateImpl(dbManager);
  }

  /**
   * ServiceContainer에 어플리케이션의 서비스를 추가하는 메소드
   * @param service
   */
  @Override
  public void addService(Service service) {
    services.add(service);
  }

  /**
   * application에 add되어 있는 service에 대한 init 작업을 수행 <br/>
   * service에 대한 초기 설정값을 세팅하는 메소드.
   * @param args
   */
  @Override
  public void initServices(Args args) {
    services.init(args);
  }

  /**
   * start up the app.<br/>
   *
   */
  public void startup() {
    p2pNode.setNodeDelegate(nodeDelegate);
    resetP2PNode();
  }

  @Override
  public void shutdown() {
    logger.info("******** begin to shutdown ********");
    synchronized (dbManager.getRevokingStore()) {
      closeRevokingStore();
      closeAllStore();
    }
    closeConnection();
    dbManager.stopRepushThread();
    logger.info("******** end to shutdown ********");
  }

  /**
   * application에 add되어 있는 service를 start하는 메소드 <br/>
   */
  @Override
  public void startServices() {
    services.start();
  }

  @Override
  public void shutdownServices() {
    services.stop();
  }

  @Override
  public Node getP2pNode() {
    return p2pNode;
  }

  @Override
  public BlockStore getBlockStoreS() {
    return blockStoreDb;
  }

  @Override
  public Manager getDbManager() {
    return dbManager;
  }

  public boolean isProducer() {
    return isProducer;
  }

  public void setIsProducer(boolean producer) {
    isProducer = producer;
  }

  private void closeConnection() {
    logger.info("******** begin to shutdown connection ********");
    try {
      p2pNode.close();
    } catch (Exception e) {
      logger.info("failed to close p2pNode. " + e);
    } finally {
      logger.info("******** end to shutdown connection ********");
    }
  }

  private void closeRevokingStore() {
    dbManager.getRevokingStore().shutdown();
  }

  private void closeAllStore() {
//    if (dbManager.getRevokingStore().getClass() == SnapshotManager.class) {
//      ((SnapshotManager) dbManager.getRevokingStore()).getDbs().forEach(IRevokingDB::close);
//    } else {
//      dbManager.closeAllStore();
//    }
    dbManager.closeAllStore();
  }

}
