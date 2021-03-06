package com.wizbl.core.db.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import lombok.extern.slf4j.Slf4j;
import com.wizbl.common.utils.ByteArray;
import com.wizbl.core.capsule.AccountCapsule;
import com.wizbl.core.capsule.AssetIssueCapsule;
import com.wizbl.core.capsule.BlockCapsule;
import com.wizbl.core.capsule.ExchangeCapsule;
import com.wizbl.core.capsule.TransactionCapsule;
import com.wizbl.core.db.Manager;
import com.wizbl.protos.Contract.AssetIssueContract;
import com.wizbl.protos.Protocol.Transaction.Contract.ContractType;

@Slf4j
public class AssetUpdateHelper {

  private Manager dbManager;

  private HashMap<String, byte[]> assetNameToIdMap = new HashMap<>();

  public AssetUpdateHelper(Manager dbManager) {
    this.dbManager = dbManager;
  }

  /**
   *  블록체인 전체의 Asset 정보, Exchange 정보, Account별 Asset 정보를 갱신하는 메소드 <br/>
   *  작업은 초기화 -> Asset 정보 갱신 -> Exchange 정보 갱신 -> Account별 Asset 정보 갱신 -> 작업 마무리 순으로 진행됨.
   */
  public void doWork() {
    long start = System.currentTimeMillis();
    logger.info("Start updating the asset");
    init();
    updateAsset();
    updateExchange();
    updateAccount();
    finish();
    logger.info(
        "Complete the asset update,Total time：{} milliseconds", System.currentTimeMillis() - start);
  }

  /**
   * - AssetIssueV2Store, ExchangeV2Store 정보 초기화(reset) <br/>
   * - reset의 구체적인 기능은 LevelDbDataSourceImpl 클래스의 reset() 메소드 참조
   */
  public void init() {
    if (dbManager.getAssetIssueV2Store().iterator().hasNext()) {
      logger.warn("AssetIssueV2Store is not empty");
    }
    dbManager.getAssetIssueV2Store().reset();
    if (dbManager.getExchangeV2Store().iterator().hasNext()) {
      logger.warn("ExchangeV2Store is not empty");
    }
    dbManager.getExchangeV2Store().reset();
    dbManager.getDynamicPropertiesStore().saveTokenIdNum(1000000L);
  }

  /**
   * 1번 블록부터 LatestBlockHeaderNumber까지의 블록에 저장된 AssetIssueContract type의 데이터를 전부 조회하는 메소드
   * @return
   */
  public List<AssetIssueCapsule> getAllAssetIssues() {

    List<AssetIssueCapsule> result = new ArrayList<>();

    long latestBlockHeaderNumber =
        dbManager.getDynamicPropertiesStore().getLatestBlockHeaderNumber();
    long blockNum = 1;
    while (blockNum <= latestBlockHeaderNumber) {
      if (blockNum % 100000 == 0) {
        logger.info("The number of block that have processed：{}", blockNum);
      }
      try {
        BlockCapsule block = dbManager.getBlockByNum(blockNum);
        for (TransactionCapsule transaction : block.getTransactions()) {
          if (transaction.getInstance().getRawData().getContract(0).getType()
              == ContractType.AssetIssueContract) {
            AssetIssueContract obj =
                transaction
                    .getInstance()
                    .getRawData()
                    .getContract(0)
                    .getParameter()
                    .unpack(AssetIssueContract.class);

            AssetIssueCapsule assetIssueCapsule = new AssetIssueCapsule(obj);

            result.add(dbManager.getAssetIssueStore().get(assetIssueCapsule.createDbKey()));
          }
        }

      } catch (Exception e) {
        throw new RuntimeException("Block not exists,num:" + blockNum);
      }

      blockNum++;
    }
    logger.info("Total block：{}", blockNum);

    if (dbManager.getAssetIssueStore().getAllAssetIssues().size() != result.size()) {
      throw new RuntimeException("Asset num is wrong!");
    }

    return result;
  }

  /**
   * 블록체인에 존재하는 Asset 관련정보를 AssetIssueStore, AssetIssueV2Store에 갱신하는 메소드 <br/>
   * 갱신 정보는 블록체인 전체에 존재하는 AssetIssueContract 의 전체정보임.
   */
  public void updateAsset() {
    long tokenIdNum = dbManager.getDynamicPropertiesStore().getTokenIdNum();
    long count = 0;

    List<AssetIssueCapsule> assetIssueCapsuleList = getAllAssetIssues();
    for (AssetIssueCapsule assetIssueCapsule : assetIssueCapsuleList) {
      tokenIdNum++;
      count++;

      // init()을 통해서 DB가 초기화 되지만 중복을 막기 위해서 최근 tokenIdNum 이후로 번호 부여
      assetIssueCapsule.setId(Long.toString(tokenIdNum));
      dbManager.getAssetIssueStore().put(assetIssueCapsule.createDbKey(), assetIssueCapsule);
      assetIssueCapsule.setPrecision(0);
      dbManager.getAssetIssueV2Store().put(assetIssueCapsule.createDbV2Key(), assetIssueCapsule);

      assetNameToIdMap.put(ByteArray.toStr(assetIssueCapsule.createDbKey()), assetIssueCapsule.createDbV2Key());
    }
    dbManager.getDynamicPropertiesStore().saveTokenIdNum(tokenIdNum);

    logger.info("Complete the asset store update,Total assets：{}", count);
  }

  public void updateExchange() {
    long count = 0;

    for (ExchangeCapsule exchangeCapsule : dbManager.getExchangeStore().getAllExchanges()) {
      count++;
      if (!Arrays.equals(exchangeCapsule.getFirstTokenId(), "_".getBytes())) {
        exchangeCapsule.setFirstTokenId(assetNameToIdMap.get(ByteArray.toStr(exchangeCapsule.getFirstTokenId())));
      }

      if (!Arrays.equals(exchangeCapsule.getSecondTokenId(), "_".getBytes())) {
        exchangeCapsule.setSecondTokenId(assetNameToIdMap.get(ByteArray.toStr(exchangeCapsule.getSecondTokenId())));
      }

      dbManager.getExchangeV2Store().put(exchangeCapsule.createDbKey(), exchangeCapsule);
    }

    logger.info("Complete the exchange store update,Total exchanges：{}", count);
  }

  /**
   * AccountStore에 저장된 Account의 Asset관련된 내용을 갱신하는 메소드 <br/>
   * 이는 AccountStore에 저장된 전체 Account에 대해서 수행되는 작업임.
   */
  public void updateAccount() {
    long count = 0;

    Iterator<Entry<byte[], AccountCapsule>> iterator = dbManager.getAccountStore().iterator();
    while (iterator.hasNext()) {
      AccountCapsule accountCapsule = iterator.next().getValue();

      accountCapsule.clearAssetV2();
      if (accountCapsule.getAssetMap().size() != 0) {
        HashMap<String, Long> map = new HashMap<>();
        for (Map.Entry<String, Long> entry : accountCapsule.getAssetMap().entrySet()) {
          map.put(ByteArray.toStr(assetNameToIdMap.get(entry.getKey())), entry.getValue());
        }

        accountCapsule.addAssetMapV2(map);
      }

      accountCapsule.clearFreeAssetNetUsageV2();
      if (accountCapsule.getAllFreeAssetNetUsage().size() != 0) {
        HashMap<String, Long> map = new HashMap<>();
        for (Map.Entry<String, Long> entry : accountCapsule.getAllFreeAssetNetUsage().entrySet()) {
          map.put(ByteArray.toStr(assetNameToIdMap.get(entry.getKey())), entry.getValue());
        }
        accountCapsule.addAllFreeAssetNetUsageV2(map);
      }

      accountCapsule.clearLatestAssetOperationTimeV2();
      if (accountCapsule.getLatestAssetOperationTimeMap().size() != 0) {
        HashMap<String, Long> map = new HashMap<>();
        for (Map.Entry<String, Long> entry :
            accountCapsule.getLatestAssetOperationTimeMap().entrySet()) {
          map.put(ByteArray.toStr(assetNameToIdMap.get(entry.getKey())), entry.getValue());
        }
        accountCapsule.addAllLatestAssetOperationTimeV2(map);
      }

      if (!accountCapsule.getAssetIssuedName().isEmpty()) {
        accountCapsule.setAssetIssuedID(
            assetNameToIdMap.get(
                ByteArray.toStr(accountCapsule.getAssetIssuedName().toByteArray())));
      }

      dbManager.getAccountStore().put(accountCapsule.createDbKey(), accountCapsule);

      if (count % 50000 == 0) {
        logger.info("The number of accounts that have completed the update ：{}", count);
      }
      count++;
    }

    logger.info("Complete the account store update,Total assets：{}", count);
  }

  /**
   * DynamicPropertiesStore에 Token(Asset) 업데이트 작업이 완료되었음을 알려주는 메소드
   */
  public void finish() {
    dbManager.getDynamicPropertiesStore().saveTokenUpdateDone(1);
    assetNameToIdMap.clear();
  }
}
