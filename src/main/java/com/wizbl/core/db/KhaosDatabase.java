package com.wizbl.core.db;

import com.wizbl.common.utils.Sha256Hash;
import com.wizbl.core.capsule.BlockCapsule;
import com.wizbl.core.capsule.BlockCapsule.BlockId;
import com.wizbl.core.exception.BadNumberBlockException;
import com.wizbl.core.exception.NonCommonBlockException;
import com.wizbl.core.exception.UnLinkedBlockException;
import javafx.util.Pair;
import lombok.Getter;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 전체 노드 메모리에 KhaosDB를 보유하고 있음. <br/>
 * KhaosDB는 일정 시간 내에 생성되는 모든 새로운 포크 체인을 저장함. <br/>
 * 검증노드(witness)가 자신의 활성 체인을 새로운 주체인으로 신속하게 전환가능 <br/>
 */
@Component
public class KhaosDatabase extends Brte2Database {

  /**
   * Push the block in the KhoasDB. <br/>
   * - KhaosStore에 저장된 블록가운데 blk의 parent 블록이 존재하는 경우 blk와 parent 블록 간에 부모-자식 관계를 설정한 후
   * blk를 KhaosStore에 저장함. <br/>
   * - 이 때 blk의 블록 번호가 KhaosStore의 head의 블록 번호보다 큰 경우에는 head의 block를 반환하게 됨. <br/>
   * @param blk
   * @return BlockCapsule
   * @throws UnLinkedBlockException parent block이 존재하지 않는 경우에 발생하는 예외
   * @throws BadNumberBlockException 부모-자식간의 관계이지만 블록번호의 차이가 1이 아닌 경우에 발생하는 예외
   */
  public BlockCapsule push(BlockCapsule blk) throws UnLinkedBlockException, BadNumberBlockException {
    KhaosBlock block = new KhaosBlock(blk);
    if (head != null && block.getParentHash() != Sha256Hash.ZERO_HASH) {
      KhaosBlock kblock = miniStore.getByHash(block.getParentHash());
      // KhaosStore에 blk블록의 parent 블록이 존재한다면
      if (kblock != null) {
        // 해당 블록 번호가 parent 블록 번호보다 1이 더 크지 않으면 예외발생
        if (blk.getNum() != kblock.num + 1) {
          throw new BadNumberBlockException(
              "parent number :" + kblock.num + ",block number :" + blk.getNum());
        }
        // block와 kblock간에 부모 - 자식 관계를 설정해 줌.
        block.setParent(kblock);
      } else {
        // parent 블록이 없는 경우에는 부모-자식 관계 설정이 불가능하므로, miniUnlinkedStore에 별도로 저장
        miniUnlinkedStore.insert(block);
        throw new UnLinkedBlockException();
      }
    }
    // 부모-자식 관계가 설정된 block을 KhaosStore에 저장함.
    miniStore.insert(block);

    if (head == null || block.num > head.num) {
      // KhaosStore의 KhaosBlock의 head pointer를 최근에 push한 block으로 설정.
      head = block;
    }
    return head.blk;
  }

  /**
   * Find two block's most recent common parent block. <br/>
   * KhaosStore에 저장되어 있는 분기된 블록체인을 반환하는 메소드 <br/>
   * 두 khaosBlock의 공통된 parentBlockHash를 찾을 때까지 반복적으로 개별 khaosBlock의 parentHash를 조회하면서 조건을 만족할 때까지 khaosBlock 객체를 linkedList에 저장
   * @param block1 khaosStore에 신규로 저장된 blockCapsule 객체의 blockhash
   * @param block2 dynamicPropertiesStore의 latestBlockHeaderhash
   * @return new Pair<>(list1, list2) block1, block2의 공통 부모 이후의 KhaosBlock list의
   * @throws NonCommonBlockException
   */
  public Pair<LinkedList<KhaosBlock>, LinkedList<KhaosBlock>> getBranch(Sha256Hash block1, Sha256Hash block2)
      throws NonCommonBlockException {
    LinkedList<KhaosBlock> list1 = new LinkedList<>();
    LinkedList<KhaosBlock> list2 = new LinkedList<>();
    KhaosBlock kblk1 = miniStore.getByHash(block1);
    checkNull(kblk1);
    KhaosBlock kblk2 = miniStore.getByHash(block2);
    checkNull(kblk2);

    // newHead의 blockHash의 블록 번호 > latestBlockHeaderhash의 블록번호
    while (kblk1.num > kblk2.num) {
      list1.add(kblk1);
      kblk1 = kblk1.getParent();
      checkNull(kblk1);
      checkNull(miniStore.getByHash(kblk1.id));
    }

    // newHead의 blockHash의 블록 번호 < latestBlockHeaderhash의 블록번호
    while (kblk2.num > kblk1.num) {
      list2.add(kblk2);
      kblk2 = kblk2.getParent();
      checkNull(kblk2);
      checkNull(miniStore.getByHash(kblk2.id));
    }

    // 두 객체의 블록 번호가 동일하지만 두 객체가 서로 다른 경우.
    while (!Objects.equals(kblk1, kblk2)) {
      list1.add(kblk1);
      list2.add(kblk2);
      kblk1 = kblk1.getParent();
      checkNull(kblk1);
      checkNull(miniStore.getByHash(kblk1.id));
      kblk2 = kblk2.getParent();
      checkNull(kblk2);
      checkNull(miniStore.getByHash(kblk2.id));
    }

    return new Pair<>(list1, list2);
  }

  private KhaosBlock head;

  @Getter
  private KhaosStore miniStore = new KhaosStore();

  @Getter
  private KhaosStore miniUnlinkedStore = new KhaosStore();

  @Autowired
  protected KhaosDatabase(@Value("block_KDB") String dbName) {
    super(dbName);
  }

  @Override
  public void put(byte[] key, Object item) {
  }

  @Override
  public void delete(byte[] key) {
  }

  @Override
  public Object get(byte[] key) {
    return null;
  }

  @Override
  public boolean has(byte[] key) {
    return false;
  }

  void start(BlockCapsule blk) {
    this.head = new KhaosBlock(blk);
    miniStore.insert(this.head);
  }

  void setHead(KhaosBlock blk) {
    this.head = blk;
  }

  void removeBlk(Sha256Hash hash) {
    if (!miniStore.remove(hash)) {
      miniUnlinkedStore.remove(hash);
    }

    head = miniStore.numKblkMap.entrySet().stream()
        .max(Comparator.comparingLong(Map.Entry::getKey))
        .map(Map.Entry::getValue)
        .map(list -> list.get(0))
        .orElseThrow(() -> new RuntimeException("khaosDB head should not be null."));
  }

  /**
   * check if the id is contained in the KhoasDB.
   */
  public Boolean containBlock(Sha256Hash hash) {
    return miniStore.getByHash(hash) != null || miniUnlinkedStore.getByHash(hash) != null;
  }

  public Boolean containBlockInMiniStore(Sha256Hash hash) {
    return miniStore.getByHash(hash) != null;
  }

  /**
   * Get the Block form KhoasDB, if it doesn't exist ,return null.
   */
  public BlockCapsule getBlock(Sha256Hash hash) {
    return Stream.of(miniStore.getByHash(hash), miniUnlinkedStore.getByHash(hash))
        .filter(Objects::nonNull)
        .map(block -> block.blk)
        .findFirst()
        .orElse(null);
  }

  /**
   * Find two block's most recent common parent block.
   */
  @Deprecated
  public Pair<LinkedList<BlockCapsule>, LinkedList<BlockCapsule>> getBranch(BlockId block1, BlockId block2) {
    LinkedList<BlockCapsule> list1 = new LinkedList<>();
    LinkedList<BlockCapsule> list2 = new LinkedList<>();
    KhaosBlock kblk1 = miniStore.getByHash(block1);
    KhaosBlock kblk2 = miniStore.getByHash(block2);

    if (kblk1 != null && kblk2 != null) {
      while (!Objects.equals(kblk1, kblk2)) {
        if (kblk1.num > kblk2.num) {
          list1.add(kblk1.blk);
          kblk1 = kblk1.getParent();
        } else if (kblk1.num < kblk2.num) {
          list2.add(kblk2.blk);
          kblk2 = kblk2.getParent();
        } else {
          list1.add(kblk1.blk);
          list2.add(kblk2.blk);
          kblk1 = kblk1.getParent();
          kblk2 = kblk2.getParent();
        }
      }
    }

    return new Pair<>(list1, list2);
  }

  public BlockCapsule getHead() {
    return head.blk;
  }

  /**
   * pop the head block then remove it.
   */
  public boolean pop() {
    KhaosBlock prev = head.getParent();
    if (prev != null) {
      head = prev;
      return true;
    }
    return false;
  }

  public void setMaxSize(int maxSize) {
    miniUnlinkedStore.setMaxCapcity(maxSize);
    miniStore.setMaxCapcity(maxSize);
  }

  public static class KhaosBlock {

    public Sha256Hash getParentHash() {
      return this.blk.getParentHash();
    }

    public KhaosBlock(BlockCapsule blk) {
      this.blk = blk;
      this.id = blk.getBlockId();
      this.num = blk.getNum();
    }

    @Getter
    BlockCapsule blk;
    Reference<KhaosBlock> parent = new WeakReference<>(null);
    BlockId id;
    Boolean invalid;
    long num;

    public KhaosBlock getParent() {
      return parent == null ? null : parent.get();
    }

    public void setParent(KhaosBlock parent) {
      this.parent = new WeakReference<>(parent);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      KhaosBlock that = (KhaosBlock) o;
      return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {

      return Objects.hash(id);
    }
  } // end of KhaosBlock

  private void checkNull(Object o) throws NonCommonBlockException {
    if (o == null) {
      throw new NonCommonBlockException();
    }
  }

  public class KhaosStore {

    private HashMap<BlockId, KhaosBlock> hashKblkMap = new HashMap<>();
    // private HashMap<Sha256Hash, KhaosBlock> parentHashKblkMap = new HashMap<>();
    private int maxCapcity = 1024;

    @Getter
    private LinkedHashMap<Long, ArrayList<KhaosBlock>> numKblkMap =
        new LinkedHashMap<Long, ArrayList<KhaosBlock>>() {

          @Override
          protected boolean removeEldestEntry(Map.Entry<Long, ArrayList<KhaosBlock>> entry) {
            long minNum = Long.max(0L, head.num - maxCapcity);
            Map<Long, ArrayList<KhaosBlock>> minNumMap = numKblkMap.entrySet().stream()
                .filter(e -> e.getKey() < minNum)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            minNumMap.forEach((k, v) -> {
              numKblkMap.remove(k);
              v.forEach(b -> hashKblkMap.remove(b.id));
            });

            return false;
          }
        };

    public void setMaxCapcity(int maxCapcity) {
      this.maxCapcity = maxCapcity;
    }

    public void insert(KhaosBlock block) {
      hashKblkMap.put(block.id, block);
      numKblkMap.computeIfAbsent(block.num, listBlk -> new ArrayList<>()).add(block);
    }

    public boolean remove(Sha256Hash hash) {
      KhaosBlock block = this.hashKblkMap.get(hash);
      // Sha256Hash parentHash = Sha256Hash.ZERO_HASH;
      if (block != null) {
        long num = block.num;
        // parentHash = block.getParentHash();
        ArrayList<KhaosBlock> listBlk = numKblkMap.get(num);
        if (listBlk != null) {
          listBlk.removeIf(b -> b.id.equals(hash));
        }

        if (CollectionUtils.isEmpty(listBlk)) {
          numKblkMap.remove(num);
        }

        this.hashKblkMap.remove(hash);
        return true;
      }
      return false;
    }

    public List<KhaosBlock> getBlockByNum(Long num) {
      return numKblkMap.get(num);
    }

    public KhaosBlock getByHash(Sha256Hash hash) {
      return hashKblkMap.get(hash);
    }

    public int size() {
      return hashKblkMap.size();
    }

  } // end of KhaosStore


  // only for unittest
  public BlockCapsule getParentBlock(Sha256Hash hash) {
    return Stream.of(miniStore.getByHash(hash), miniUnlinkedStore.getByHash(hash))
        .filter(Objects::nonNull)
        .map(KhaosBlock::getParent)
        .map(khaosBlock -> khaosBlock == null ? null : khaosBlock.blk)
        .filter(Objects::nonNull)
        .filter(b -> containBlock(b.getBlockId()))
        .findFirst()
        .orElse(null);
  }

  public boolean hasData() {
    return !this.miniStore.hashKblkMap.isEmpty();
  }
}
