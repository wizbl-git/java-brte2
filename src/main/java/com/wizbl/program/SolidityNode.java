package com.wizbl.program;

import ch.qos.logback.classic.Level;
import com.wizbl.common.application.Application;
import com.wizbl.common.application.ApplicationFactory;
import com.wizbl.common.application.Brte2ApplicationContext;
import com.wizbl.common.overlay.client.DatabaseGrpcClient;
import com.wizbl.common.overlay.discover.DiscoverServer;
import com.wizbl.common.overlay.discover.node.NodeManager;
import com.wizbl.common.overlay.server.ChannelManager;
import com.wizbl.core.Constant;
import com.wizbl.core.capsule.BlockCapsule;
import com.wizbl.core.config.DefaultConfig;
import com.wizbl.core.config.args.Args;
import com.wizbl.core.db.Manager;
import com.wizbl.core.services.RpcApiService;
import com.wizbl.core.services.http.solidity.SolidityNodeHttpApiService;
import com.wizbl.protos.Protocol.Block;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.util.StringUtils;

import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicLong;

import static com.wizbl.core.config.Parameter.ChainConstant.BLOCK_PRODUCED_INTERVAL;

@Slf4j
public class SolidityNode {

  private final Manager dbManager;

  private final DatabaseGrpcClient databaseGrpcClient;

  private final AtomicLong ID = new AtomicLong();

  private final AtomicLong remoteBlockNum = new AtomicLong();

  private final LinkedBlockingDeque<Block> blockQueue = new LinkedBlockingDeque(100);

  private final int exceptionSleepTime = 1000;

  private final boolean flag = true;

  public SolidityNode(Manager dbManager) {
    this.dbManager = dbManager;
    resolveCompatibilityIssueIfUsingFullNodeDatabase();
    ID.set(dbManager.getDynamicPropertiesStore().getLatestSolidifiedBlockNum());
    // databaseGrpcClient : config 파일에 설정한 TrustNode의 database에 연결하기 위한 client
    databaseGrpcClient = new DatabaseGrpcClient(Args.getInstance().getTrustNodeAddr());
    remoteBlockNum.set(getLastSolidityBlockNum());
  }

  private void start() {
    try {
      new Thread(() -> getBlock()).start();
      new Thread(() -> processBlock()).start();
      logger.info( "Success to start solid node, ID: {}, remoteBlockNum: {}.", ID.get(), remoteBlockNum);
    } catch (Exception e) {
      logger.error("Failed to start solid node, address: {}.", Args.getInstance().getTrustNodeAddr());
      System.exit(0);
    }
  }

  /**
   * blockQueue에 TrustNode에서 조회한 block 정보를 저장하는 메서드
   */
  private void getBlock() {
    long blockNum = ID.incrementAndGet();
    while (flag) {
      try {
        if (blockNum > remoteBlockNum.get()) {
          sleep(BLOCK_PRODUCED_INTERVAL);
          remoteBlockNum.set(getLastSolidityBlockNum());
          continue;
        }
        Block block = getBlockByNum(blockNum);
        blockQueue.put(block);
        blockNum = ID.incrementAndGet();
      } catch (Exception e) {
        logger.error("Failed to get block {}, reason: {}.", blockNum, e.getMessage());
        sleep(exceptionSleepTime);
      }
    }
  }

  private void processBlock() {
    while (flag) {
      try {
        Block block = blockQueue.take();
        loopProcessBlock(block);
      } catch (Exception e) {
        logger.error(e.getMessage());
        sleep(exceptionSleepTime);
      }
    }
  }

  /**
   * 검증된 block을 Node의 DB에 저장,
   * 최신의 solid block number를 Node의 DB에 저장
   * @param block
   */
  private void loopProcessBlock(Block block) {
    while (flag) {
      long blockNum = block.getBlockHeader().getRawData().getNumber();
      try {
        dbManager.pushVerifiedBlock(new BlockCapsule(block));
        dbManager.getDynamicPropertiesStore().saveLatestSolidifiedBlockNum(blockNum);
        logger.info("Success to process block: {}, blockQueueSize: {}.", blockNum, blockQueue.size());
        return;
      } catch (Exception e) {
        logger.error("Failed to process block {}.", new BlockCapsule(block), e);
        sleep(exceptionSleepTime);
        block = getBlockByNum(blockNum);
      }
    }
  }


  private Block getBlockByNum(long blockNum) {
    while (true) {
      try {
        long time = System.currentTimeMillis();
        Block block = databaseGrpcClient.getBlock(blockNum);
        long num = block.getBlockHeader().getRawData().getNumber();
        if (num == blockNum) {
          logger.info("Success to get block: {}, cost: {}ms.", blockNum, System.currentTimeMillis() - time);
          return block;
        }else {
          logger.warn("Get block id not the same , {}, {}.", num, blockNum);
          sleep(exceptionSleepTime);
        }
      }catch (Exception e){
        logger.error("Failed to get block: {}, reason: {}.", blockNum, e.getMessage());
        sleep(exceptionSleepTime);
      }
    }
  }

  /**
   * TrustNode의 database에 저장된 마지막 solid block num을 조회하는 메서드
   * @return
   */
  private long getLastSolidityBlockNum() {
    while (true) {
      try {
        long time = System.currentTimeMillis();
        long blockNum = databaseGrpcClient.getDynamicProperties().getLastSolidityBlockNum();
        logger.info("Get last remote solid blockNum: {}, remoteBlockNum: {}, cost: {}.",
            blockNum, remoteBlockNum, System.currentTimeMillis() - time);
        return blockNum;
      } catch (Exception e) {
        logger.error("Failed to get last solid blockNum: {}, reason: {}.", remoteBlockNum.get(), e.getMessage());
        sleep(exceptionSleepTime);
      }
    }
  }

  public void sleep(long time) {
    try {
      Thread.sleep(time);
    } catch (Exception e1) {
    }
  }

  private void resolveCompatibilityIssueIfUsingFullNodeDatabase() {
    long lastSolidityBlockNum = dbManager.getDynamicPropertiesStore().getLatestSolidifiedBlockNum();
    long headBlockNum = dbManager.getHeadBlockNum();
    logger.info("headBlockNum:{}, solidityBlockNum:{}, diff:{}",
        headBlockNum, lastSolidityBlockNum, headBlockNum - lastSolidityBlockNum);
    if (lastSolidityBlockNum < headBlockNum) {
      logger.info("use fullNode database, headBlockNum:{}, solidityBlockNum:{}, diff:{}",
          headBlockNum, lastSolidityBlockNum, headBlockNum - lastSolidityBlockNum);
      dbManager.getDynamicPropertiesStore().saveLatestSolidifiedBlockNum(headBlockNum);
    }
  }

  /**
   * Start the SolidityNode.
   */
  public static void main(String[] args) {
    logger.info("Solidity node running.");
    Args.setParam(args, Constant.CONFIG_CONF);
    Args cfgArgs = Args.getInstance();

    logger.info("index switch is {}",
        BooleanUtils.toStringOnOff(BooleanUtils.toBoolean(cfgArgs.getStorage().getIndexSwitch())));
    ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger)LoggerFactory
        .getLogger(Logger.ROOT_LOGGER_NAME);
    root.setLevel(Level.toLevel(cfgArgs.getLogLevel()));

    if (StringUtils.isEmpty(cfgArgs.getTrustNodeAddr())) {
      logger.error("Trust node not set.");
      return;
    }
    cfgArgs.setSolidityNode(true);

    ApplicationContext context = new Brte2ApplicationContext(DefaultConfig.class);

    if (cfgArgs.isHelp()) {
      logger.info("Here is the help message.");
      return;
    }
    Application appT = ApplicationFactory.create(context);
    FullNode.shutdown(appT);

    //appT.init(cfgArgs);
    RpcApiService rpcApiService = context.getBean(RpcApiService.class);
    appT.addService(rpcApiService);
    //http
    SolidityNodeHttpApiService httpApiService = context.getBean(SolidityNodeHttpApiService.class);
    appT.addService(httpApiService);

    appT.initServices(cfgArgs);
    appT.startServices();
//    appT.startup();

    //Disable peer discovery for solidity node
    DiscoverServer discoverServer = context.getBean(DiscoverServer.class);
    discoverServer.close();
    ChannelManager channelManager = context.getBean(ChannelManager.class);
    channelManager.close();
    NodeManager nodeManager = context.getBean(NodeManager.class);
    nodeManager.close();

    SolidityNode node = new SolidityNode(appT.getDbManager());
    node.start();

    rpcApiService.blockUntilShutdown();
  }
}
