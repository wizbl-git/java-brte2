package com.wizbl.core.services;

import com.wizbl.common.utils.ByteArray;
import com.wizbl.core.capsule.BlockCapsule;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class WitnessProductBlockService {

  private Cache<Long, BlockCapsule> historyBlockCapsuleCache = CacheBuilder.newBuilder()
      .initialCapacity(200).maximumSize(200).build();

  private Map<String, CheatWitnessInfo> cheatWitnessInfoMap = new HashMap<>();

  public static class CheatWitnessInfo {

    private AtomicInteger times = new AtomicInteger(0);
    private long latestBlockNum;
    private Set<BlockCapsule> blockCapsuleSet = new HashSet<>();
    private long time;

    public CheatWitnessInfo increment() {
      times.incrementAndGet();
      return this;
    }

    public AtomicInteger getTimes() {
      return times;
    }

    public CheatWitnessInfo setTimes(AtomicInteger times) {
      this.times = times;
      return this;
    }

    public long getLatestBlockNum() {
      return latestBlockNum;
    }

    public CheatWitnessInfo setLatestBlockNum(long latestBlockNum) {
      this.latestBlockNum = latestBlockNum;
      return this;
    }

    public Set<BlockCapsule> getBlockCapsuleSet() {
      return new HashSet<>(blockCapsuleSet);
    }

    public CheatWitnessInfo clear() {
      blockCapsuleSet.clear();
      return this;
    }

    public CheatWitnessInfo add(BlockCapsule blockCapsule) {
      blockCapsuleSet.add(blockCapsule);
      return this;
    }

    public CheatWitnessInfo setBlockCapsuleSet(Set<BlockCapsule> blockCapsuleSet) {
      this.blockCapsuleSet = new HashSet<>(blockCapsuleSet);
      return this;
    }

    public long getTime() {
      return time;
    }

    public CheatWitnessInfo setTime(long time) {
      this.time = time;
      return this;
    }

    @Override
    public String toString() {
      return "{" +
          "times=" + times.get() +
          ", time=" + time +
          ", latestBlockNum=" + latestBlockNum +
          ", blockCapsuleSet=" + blockCapsuleSet +
          '}';
    }
  }

  /**
   * HistoryBlockCapsuleCache에서 block의 블록 번호와 일치하는 BlockCapsule을 조회함.<br/>
   * historyBlockCapsuleCache와 block간에 witness가 동일하지만, 두 블록의 blockId가 동일하지 않은 경우에는 cheatWitnessInfoMap에
   * witness 주소와 cheatWitnessInfo 객체를 저장함.
   * 그렇지 않은 경우에는 historyBlockCapsuleCache에 block 정보를 저장함.
   * @param block
   */
  public void validWitnessProductTwoBlock(BlockCapsule block) {
    try {
      BlockCapsule blockCapsule = historyBlockCapsuleCache.getIfPresent(block.getNum());
      if (blockCapsule != null
            && Arrays.equals(blockCapsule.getWitnessAddress().toByteArray(), block.getWitnessAddress().toByteArray())
            && !Arrays.equals(block.getBlockId().getBytes(), blockCapsule.getBlockId().getBytes())) {
        String key = ByteArray.toHexString(block.getWitnessAddress().toByteArray());
        if (!cheatWitnessInfoMap.containsKey(key)) {
          CheatWitnessInfo cheatWitnessInfo = new CheatWitnessInfo();
          cheatWitnessInfoMap.put(key, cheatWitnessInfo);
        }
        cheatWitnessInfoMap.get(key).clear().setTime(System.currentTimeMillis())
            .setLatestBlockNum(block.getNum()).add(block).add(blockCapsule).increment();
      } else {
        historyBlockCapsuleCache.put(block.getNum(), block);
      }
    } catch (Exception e) {
      logger.error("valid witness same time product two block fail! blockNum: {}, blockHash: {}",
          block.getNum(), block.getBlockId().toString(), e);
    }
  }

  public Map<String, CheatWitnessInfo> queryCheatWitnessInfo() {
    return cheatWitnessInfoMap;
  }
}
