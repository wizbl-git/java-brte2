package com.wizbl.core.services;

import com.wizbl.common.application.Application;
import com.wizbl.common.application.Service;
import com.wizbl.common.application.Brte2ApplicationContext;
import com.wizbl.common.backup.BackupManager;
import com.wizbl.common.backup.BackupManager.BackupStatusEnum;
import com.wizbl.common.backup.BackupServer;
import com.wizbl.common.crypto.ECKey;
import com.wizbl.common.utils.ByteArray;
import com.wizbl.common.utils.StringUtil;
import com.wizbl.core.capsule.BlockCapsule;
import com.wizbl.core.capsule.WitnessCapsule;
import com.wizbl.core.config.Parameter.ChainConstant;
import com.wizbl.core.config.args.Args;
import com.wizbl.core.db.Manager;
import com.wizbl.core.exception.*;
import com.wizbl.core.net.message.BlockMessage;
import com.wizbl.core.witness.BlockProductionCondition;
import com.wizbl.core.witness.WitnessController;
import com.google.common.collect.Maps;
import com.google.protobuf.ByteString;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.wizbl.core.witness.BlockProductionCondition.NOT_MY_TURN;

@Slf4j
public class WitnessService implements Service {

  private static final int MIN_PARTICIPATION_RATE
          = Args.getInstance().getMinParticipationRate(); // MIN_PARTICIPATION_RATE * 1%
  private static final int PRODUCE_TIME_OUT = 500; // ms
  private Application brte2App;
  @Getter
  protected Map<ByteString, WitnessCapsule> localWitnessStateMap = Maps.newHashMap(); //  <address,WitnessCapsule>
  private Thread generateThread;

  private volatile boolean isRunning = false;
  private Map<ByteString, byte[]> privateKeyMap = Maps.newHashMap();
  private volatile boolean needSyncCheck = Args.getInstance().isNeedSyncCheck();

  private Manager manager;

  private WitnessController controller;

  private Brte2ApplicationContext context;

  private BackupManager backupManager;

  private BackupServer backupServer;

  private AtomicInteger dupBlockCount = new AtomicInteger(0);
  private AtomicLong dupBlockTime = new AtomicLong(0);
  private long blockCycle = ChainConstant.BLOCK_PRODUCED_INTERVAL * ChainConstant.MAX_ACTIVE_WITNESS_NUM;

  /**
   * Construction method.
   */
  public WitnessService(Application brte2App, Brte2ApplicationContext context) {
    this.brte2App = brte2App;
    this.context = context;
    backupManager = context.getBean(BackupManager.class);
    backupServer = context.getBean(BackupServer.class);
    generateThread = new Thread(scheduleProductionLoop);
    manager = brte2App.getDbManager();
    manager.setWitnessService(this);
    controller = manager.getWitnessController();
    new Thread(() -> {
      while (needSyncCheck) {
        try {
          Thread.sleep(100);
        } catch (Exception e) {
        }
      }
      backupServer.initServer();
    }).start();
  }

  /**
   * Cycle thread to generate blocks
   */
  private Runnable scheduleProductionLoop =
      () -> {
        if (localWitnessStateMap == null || localWitnessStateMap.keySet().isEmpty()) {
          logger.error("LocalWitnesses is null");
          return;
        }

        while (isRunning) {
          try {
            if (this.needSyncCheck) {
              Thread.sleep(500L);
            } else {
              DateTime time = DateTime.now();
              // 1000 - ((현재 시각의 초 * 1000 + 현재 시각의 millisecond) % 1000)
              long timeToNextSecond = ChainConstant.BLOCK_PRODUCED_INTERVAL - (time.getSecondOfMinute() * 1000 + time.getMillisOfSecond()) % ChainConstant.BLOCK_PRODUCED_INTERVAL;
              if (timeToNextSecond < 50L) {
                timeToNextSecond = timeToNextSecond + ChainConstant.BLOCK_PRODUCED_INTERVAL;
              }
              DateTime nextTime = time.plus(timeToNextSecond);
              logger.debug("ProductionLoop sleep : " + timeToNextSecond + " ms,next time:" + nextTime);
              Thread.sleep(timeToNextSecond);
            }
            this.blockProductionLoop();
          } catch (InterruptedException ex) {
            logger.info("ProductionLoop interrupted");
          } catch (Exception ex) {
            logger.error("unknown exception happened in witness loop", ex);
          } catch (Throwable throwable) {
            logger.error("unknown throwable happened in witness loop", throwable);
          }
        }
      };

  /**
   * Loop to generate blocks
   */
  private void blockProductionLoop() throws InterruptedException {
    BlockProductionCondition result = this.tryProduceBlock();

    if (result == null) {
      logger.warn("Result is null");
      return;
    }

    if (result.ordinal() <= NOT_MY_TURN.ordinal()) {
      logger.debug(result.toString());
    } else {
      logger.info(result.toString());
    }
  }

  /**
   * Generate and broadcast blocks
   * 1. Backup Status 검증 <br/>
   * 2. Witness duplicate 여부 확인 <br/>
   * 3. data sync 필요성 여부 체크 <br/>
   * 4. 블록 생성 과정에서의 최소 참여율 조건 충족 여부 체크 <br/>
   * 5. 선출된(블록 생성 권한을 인정받은) Witness인지를 체크 <br/>
   * 6. 블록 생성을 위한 시각이 되었는지를 체크<br/>
   * 7. now > latestBlockHeaderTimestamp의 조건 충족 여부를 체크 <br/>
   * 8. Schedule에 부합되는 Witness가 블록 생성 작업을 진행하는지 여부를 체크 <br/>
   * 9. 예정된 블록 생성 시각와 현재 시각을 비교하여 원래 예정 시각에서 지연(LAG)되고 있는지 여부를 체크 <br/>
   * 10. 블록 생성이 예정된 witness가 유효한 privateKey를 가지고 있는지 체크 <br/>
   * 11. generate Block 작업 진행 <br/>
   * 12. Block 생성에 걸린 시간이 Time out 조건에 해당되는지 여부를 체크 <br/>
   * 13. 생성된 블록 전파(broadcast)
   */
  private BlockProductionCondition tryProduceBlock() throws InterruptedException {
    logger.info("Try Produce Block");
    // 1. Backup Status 검증
    if (!backupManager.getStatus().equals(BackupStatusEnum.MASTER)) {
      return BlockProductionCondition.BACKUP_STATUS_IS_NOT_MASTER;
    }
    // 2. Witness duplicate 여부 확인
    if (dupWitnessCheck()) {
      return BlockProductionCondition.DUP_WITNESS;
    }
    long now = DateTime.now().getMillis() + 50L; // TODO - 50L은 어떤 의미를 지닌 수치인가???
    // 3. data sync 필요성 여부 체크
    if (this.needSyncCheck) {
      long nexSlotTime = controller.getSlotTime(1);
      if (nexSlotTime > now) { // check sync during first loop
        needSyncCheck = false;
        Thread.sleep(nexSlotTime - now); //Processing Time Drift later
        now = DateTime.now().getMillis();
      } else {
        logger.debug("Not sync ,now:{},headBlockTime:{},headBlockNumber:{},headBlockId:{}",
            new DateTime(now),
            new DateTime(this.brte2App.getDbManager().getDynamicPropertiesStore()
                .getLatestBlockHeaderTimestamp()),
            this.brte2App.getDbManager().getDynamicPropertiesStore().getLatestBlockHeaderNumber(),
            this.brte2App.getDbManager().getDynamicPropertiesStore().getLatestBlockHeaderHash());
        return BlockProductionCondition.NOT_SYNCED;
      }
    }

    // 4. 블록 생성 과정에서의 최소 참여율 조건 충족 여부 체크
    final int participation = this.controller.calculateParticipationRate();
    if (participation < MIN_PARTICIPATION_RATE) {
      logger.warn(
          "Participation[" + participation + "] <  MIN_PARTICIPATION_RATE[" + MIN_PARTICIPATION_RATE + "]");

      if (logger.isDebugEnabled()) {
        this.controller.dumpParticipationLog();
      }

      return BlockProductionCondition.LOW_PARTICIPATION;
    }

    // 5. 선출된(블록 생성 권한을 인정받은) Witness인지를 체크
    if (!controller.activeWitnessesContain(this.getLocalWitnessStateMap().keySet())) {
      logger.info("Unelected. Elected Witnesses: {}",
          StringUtil.getAddressStringList(controller.getActiveWitnesses()));
      return BlockProductionCondition.UNELECTED;
    }

    try {

      BlockCapsule block;

      synchronized (brte2App.getDbManager()) {
        long slot = controller.getSlotAtTime(now);
        logger.debug("Slot:" + slot);
        // 6. 블록 생성을 위한 시각이 되었는지를 체크
        if (slot == 0) {    // now < witnessController.getSlotTime(1) 인 경우에 0을 반환함.
          logger.info("Not time yet,now:{},headBlockTime:{},headBlockNumber:{},headBlockId:{}",
              new DateTime(now),
              new DateTime(this.brte2App.getDbManager().getDynamicPropertiesStore().getLatestBlockHeaderTimestamp()),
              this.brte2App.getDbManager().getDynamicPropertiesStore().getLatestBlockHeaderNumber(),
              this.brte2App.getDbManager().getDynamicPropertiesStore().getLatestBlockHeaderHash());
          return BlockProductionCondition.NOT_TIME_YET;
        }

        // 7. now > latestBlockHeaderTimestamp의 조건 충족 여부를 체크
        if (now < controller.getManager().getDynamicPropertiesStore().getLatestBlockHeaderTimestamp()) {
          logger.warn("have a timestamp:{} less than or equal to the previous block:{}",
              new DateTime(now), new DateTime(this.brte2App.getDbManager().getDynamicPropertiesStore().getLatestBlockHeaderTimestamp()));
          return BlockProductionCondition.EXCEPTION_PRODUCING_BLOCK;
        }

        // 8. Schedule에 부합되는 Witness가 블록 생성 작업을 진행하는지 여부를 체크
        final ByteString scheduledWitness = controller.getScheduledWitness(slot);
        if (!this.getLocalWitnessStateMap().containsKey(scheduledWitness)) {
          logger.info("It's not my turn, ScheduledWitness[{}],slot[{}],abSlot[{}],",
              ByteArray.toHexString(scheduledWitness.toByteArray()), slot,
              controller.getAbSlotAtTime(now));
          return NOT_MY_TURN;
        }

        // 9. 예정된 블록 생성 시각와 현재 시각을 비교하여 원래 예정 시각에서 지연(LAG)되고 있는지 여부를 체크
        long scheduledTime = controller.getSlotTime(slot);
        if (scheduledTime - now > PRODUCE_TIME_OUT) {
          return BlockProductionCondition.LAG;
        }

        // 10. 블록 생성이 예정된 witness가 유효한 privateKey를 가지고 있는지 체크
        if (!privateKeyMap.containsKey(scheduledWitness)) {
          return BlockProductionCondition.NO_PRIVATE_KEY;
        }


        controller.getManager().lastHeadBlockIsMaintenance();

        controller.setGeneratingBlock(true);

        // 11. generate Block 작업 진행
        block = generateBlock(scheduledTime, scheduledWitness, controller.lastHeadBlockIsMaintenance());
      }

      if (block == null) {
        logger.warn("exception when generate block");
        return BlockProductionCondition.EXCEPTION_PRODUCING_BLOCK;
      }

      // 12. Block 생성에 걸린 시간이 Time out 조건에 해당되는지 여부를 체크
      int blockProducedTimeOut = Args.getInstance().getBlockProducedTimeOut();

      long timeout = Math.min(
              ChainConstant.BLOCK_PRODUCED_INTERVAL * blockProducedTimeOut / 100 + 500,
              ChainConstant.BLOCK_PRODUCED_INTERVAL);
      if (DateTime.now().getMillis() - now > timeout) {
        logger.warn("Task timeout ( > {}ms)，startTime:{},endTime:{}", timeout, new DateTime(now),
            DateTime.now());
        brte2App.getDbManager().eraseBlock();
        return BlockProductionCondition.TIME_OUT;
      }

      logger.info(
          "Produce block successfully, blockNumber:{}, abSlot[{}], blockId:{}, transactionSize:{}, blockTime:{}, parentBlockId:{}",
          block.getNum(), controller.getAbSlotAtTime(now), block.getBlockId(),
          block.getTransactions().size(),
          new DateTime(block.getTimeStamp()),
          block.getParentHash());

      // 13. 생성된 블록 전파(broadcast)
      broadcastBlock(block);

      return BlockProductionCondition.PRODUCED;
    } catch (Brte2Exception e) {
      logger.error(e.getMessage(), e);
      return BlockProductionCondition.EXCEPTION_PRODUCING_BLOCK;
    } finally {
      controller.setGeneratingBlock(false);
    }
  }

  private void broadcastBlock(BlockCapsule block) {
    try {
      brte2App.getP2pNode().broadcast(new BlockMessage(block.getData()));
    } catch (Exception ex) {
      throw new RuntimeException("BroadcastBlock error");
    }
  }

  private BlockCapsule generateBlock(long when, ByteString witnessAddress, Boolean lastHeadBlockIsMaintenance)
      throws ValidateSignatureException, ContractValidateException, ContractExeException,
      UnLinkedBlockException, ValidateScheduleException, AccountResourceInsufficientException {
    return brte2App.getDbManager().generateBlock(this.localWitnessStateMap.get(witnessAddress), when,
        this.privateKeyMap.get(witnessAddress), lastHeadBlockIsMaintenance);
  }

  private boolean dupWitnessCheck() {
    if (dupBlockCount.get() == 0) {
      return false;
    }

    if (System.currentTimeMillis() - dupBlockTime.get() > dupBlockCount.get() * blockCycle) {
      dupBlockCount.set(0);
      return false;
    }

    return true;
  }

  public void processBlock(BlockCapsule block) {
    if (block.generatedByMyself) {
      return;
    }

    if (System.currentTimeMillis() - block.getTimeStamp() > ChainConstant.BLOCK_PRODUCED_INTERVAL) {
      return;
    }

    if (!privateKeyMap.containsKey(block.getWitnessAddress())) {
      return;
    }

    if (dupBlockCount.get() == 0) {
      dupBlockCount.set(new Random().nextInt(10));
    } else {
      dupBlockCount.set(10);
    }

    dupBlockTime.set(System.currentTimeMillis());
  }

  /**
   * Initialize the local witnesses
   */
  @Override
  public void init() {
    Args.getInstance().getLocalWitnesses().getPrivateKeys().forEach(key -> {
      byte[] privateKey = ByteArray.fromHexString(key);
      final ECKey ecKey = ECKey.fromPrivate(privateKey);
      byte[] address = ecKey.getAddress();
      WitnessCapsule witnessCapsule = this.brte2App.getDbManager().getWitnessStore()
          .get(address);
      // need handle init witness
      if (null == witnessCapsule) {
        logger.warn("WitnessCapsule[" + address + "] is not in witnessStore");
        witnessCapsule = new WitnessCapsule(ByteString.copyFrom(address));
      }

      this.privateKeyMap.put(witnessCapsule.getAddress(), privateKey);
      this.localWitnessStateMap.put(witnessCapsule.getAddress(), witnessCapsule);
    });

  }

  @Override
  public void init(Args args) {
    //this.privateKey = args.getPrivateKeys();
    init();
  }

  @Override
  public void start() {
    isRunning = true;
    generateThread.start();

  }

  @Override
  public void stop() {
    isRunning = false;
    generateThread.interrupt();
  }
}
