package com.wizbl.common.utils;

import com.wizbl.core.Wallet;
import com.wizbl.core.capsule.BlockCapsule;
import com.wizbl.core.config.Parameter.ForkBlockVersionConsts;
import com.wizbl.core.config.Parameter.ForkBlockVersionEnum;
import com.wizbl.core.db.Manager;
import com.google.common.collect.Maps;
import com.google.common.collect.Streams;
import com.google.protobuf.ByteString;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 블록체인의 분기에서 발생하는 버전 관리를 하는 클래스로 파악됨.<br/>
 * 블록 번호가 4727890 을 기준 혹은 block.version = 5 의 옵션값을 기준으로 버전을 나누는 것으로 보여짐.<br/>
 * 이는 EnergyLimit의 활용과 관련하여 버전 변경이 있는 것으로 추정함.<br/>
 * block.version = 5 는 Oddyssey 3.2 버전의 hardfork임.
 */
//TODO Fork 기준은 트론의
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ForkController {

  private static final byte VERSION_DOWNGRADE = (byte) 0;
  private static final byte VERSION_UPGRADE = (byte) 1;
  private static final byte[] check;

  static {
    check = new byte[1024];
    Arrays.fill(check, VERSION_UPGRADE);
  }

  @Getter
  private Manager manager;

  public void init(Manager manager) {
    this.manager = manager;
  }

  public boolean pass(ForkBlockVersionEnum forkBlockVersionEnum) {
    return pass(forkBlockVersionEnum.getValue());
  }

  /**
   * ENERGY_LIMIT_HARD_FORK 통과 여부를 확인하는 메소드
   * @param version
   * @return true or false
   */
  public synchronized boolean pass(int version) {
    if (version == ForkBlockVersionConsts.ENERGY_LIMIT) {
      return checkForEnergyLimit();
    }

    byte[] stats = manager.getDynamicPropertiesStore().statsByVersion(version);
    return check(stats);
  }

  // when block.version = 5,
  // it make block use new energy to handle transaction when block number >= 4727890L.
  // version !=5, skip this.
  private boolean checkForEnergyLimit() {
    long blockNum = manager.getDynamicPropertiesStore().getLatestBlockHeaderNumber();
    return blockNum >= 4727890L;
  }

  private boolean check(byte[] stats) {
    if (stats == null || stats.length == 0) {
      return false;
    }

    for (int i = 0; i < stats.length; i++) {
      if (check[i] != stats[i]) {
        return false;
      }
    }

    return true;
  }

  private void downgrade(int version, int slot) {
    for (ForkBlockVersionEnum versionEnum : ForkBlockVersionEnum.values()) {
      int versionValue = versionEnum.getValue();
      if (versionValue > version) {
        byte[] stats = manager.getDynamicPropertiesStore().statsByVersion(versionValue);
        if (!check(stats) && Objects.nonNull(stats)) {
          stats[slot] = VERSION_DOWNGRADE;
          manager.getDynamicPropertiesStore().statsByVersion(versionValue, stats);
        }
      }
    }
  }

  public synchronized void update(BlockCapsule blockCapsule) {
    List<ByteString> witnesses = manager.getWitnessController().getActiveWitnesses();
    ByteString witness = blockCapsule.getWitnessAddress();
    int slot = witnesses.indexOf(witness);
    if (slot < 0) {
      return;
    }

    int version = blockCapsule.getInstance().getBlockHeader().getRawData().getVersion();
    if (version < ForkBlockVersionConsts.ENERGY_LIMIT) {
      return;
    }

    downgrade(version, slot);

    byte[] stats = manager.getDynamicPropertiesStore().statsByVersion(version);
    if (check(stats)) {
      return;
    }

    if (Objects.isNull(stats) || stats.length != witnesses.size()) {
      stats = new byte[witnesses.size()];
    }

    stats[slot] = VERSION_UPGRADE;
    manager.getDynamicPropertiesStore().statsByVersion(version, stats);
    logger.info(
        "*******update hard fork:{}, witness size:{}, slot:{}, witness:{}, version:{}",
        Streams.zip(witnesses.stream(), Stream.of(ArrayUtils.toObject(stats)), Maps::immutableEntry)
            .map(e -> Maps.immutableEntry(Wallet.encode58Check(e.getKey().toByteArray()), e.getValue()))
            .map(e -> Maps.immutableEntry(StringUtils.substring(e.getKey(), e.getKey().length() - 4), e.getValue()))
            .collect(Collectors.toList()),
        witnesses.size(),
        slot,
        Wallet.encode58Check(witness.toByteArray()),
        version);
  }

  public synchronized void reset() {
    for (ForkBlockVersionEnum versionEnum : ForkBlockVersionEnum.values()) {
      int versionValue = versionEnum.getValue();
      byte[] stats = manager.getDynamicPropertiesStore().statsByVersion(versionValue);
      if (!check(stats) && Objects.nonNull(stats)) {
        Arrays.fill(stats, VERSION_DOWNGRADE);
        manager.getDynamicPropertiesStore().statsByVersion(versionValue, stats);
      }
    }
  }

  public static ForkController instance() {
    return ForkControllerEnum.INSTANCE.getInstance();
  }

  private enum ForkControllerEnum {
    INSTANCE;

    private ForkController instance;

    ForkControllerEnum() {
      instance = new ForkController();
    }

    private ForkController getInstance() {
      return instance;
    }
  }
}
