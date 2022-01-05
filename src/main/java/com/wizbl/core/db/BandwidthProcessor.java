package com.wizbl.core.db;

import static com.wizbl.protos.Protocol.Transaction.Contract.ContractType.TransferAssetContract;

import com.google.protobuf.ByteString;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import com.wizbl.common.utils.ByteArray;
import com.wizbl.core.Constant;
import com.wizbl.core.capsule.AccountCapsule;
import com.wizbl.core.capsule.AssetIssueCapsule;
import com.wizbl.core.capsule.TransactionCapsule;
import com.wizbl.core.exception.AccountResourceInsufficientException;
import com.wizbl.core.exception.ContractValidateException;
import com.wizbl.core.exception.TooBigTransactionResultException;
import com.wizbl.protos.Contract.TransferAssetContract;
import com.wizbl.protos.Contract.TransferContract;
import com.wizbl.protos.Protocol.Transaction.Contract;

@Slf4j
public class BandwidthProcessor extends ResourceProcessor {

  public BandwidthProcessor(Manager manager) {
    super(manager);
  }

  @Override
  public void updateUsage(AccountCapsule accountCapsule) {
    long now = dbManager.getWitnessController().getHeadSlot();
    updateUsage(accountCapsule, now);
  }

  private void updateUsage(AccountCapsule accountCapsule, long now) {
    long oldNetUsage = accountCapsule.getNetUsage();
    long latestConsumeTime = accountCapsule.getLatestConsumeTime();
    accountCapsule.setNetUsage(increase(oldNetUsage, 0, latestConsumeTime, now));

    long oldFreeNetUsage = accountCapsule.getFreeNetUsage();
    long latestConsumeFreeTime = accountCapsule.getLatestConsumeFreeTime();
    accountCapsule.setFreeNetUsage(increase(oldFreeNetUsage, 0, latestConsumeFreeTime, now));

    if (dbManager.getDynamicPropertiesStore().getAllowSameTokenName() == 0) {
      Map<String, Long> assetMap = accountCapsule.getAssetMap();
      assetMap.forEach((assetName, balance) -> {
        long oldFreeAssetNetUsage = accountCapsule.getFreeAssetNetUsage(assetName);
        long latestAssetOperationTime = accountCapsule.getLatestAssetOperationTime(assetName);
        accountCapsule.putFreeAssetNetUsage(assetName,
            increase(oldFreeAssetNetUsage, 0, latestAssetOperationTime, now));
      });
    }
    Map<String, Long> assetMapV2 = accountCapsule.getAssetMapV2();
    assetMapV2.forEach((assetName, balance) -> {
      long oldFreeAssetNetUsage = accountCapsule.getFreeAssetNetUsageV2(assetName);
      long latestAssetOperationTime = accountCapsule.getLatestAssetOperationTimeV2(assetName);
      accountCapsule.putFreeAssetNetUsageV2(assetName,
          increase(oldFreeAssetNetUsage, 0, latestAssetOperationTime, now));
    });
  }

  /**
   * bytesize에 기반하여 BandWidth 비용을 계산함. <br/>
   * 단, BandWidth cost는 TransferContract(Coin 이동)에는 청구되지 않음 <br/>
   * 또한 TransferAssetContract 의 경우에는 Asset을 가지고 비용을 처리하게 된다. <br/>
   * 다른 Contract의 경우에는 AccountNet, FreeNet, TransactionFee의 순서대로 비용을 차감하게 됨.
   * @param trx
   * @param trace
   * @throws ContractValidateException
   * @throws AccountResourceInsufficientException
   * @throws TooBigTransactionResultException
   */
  @Override
  public void consume(TransactionCapsule trx, TransactionTrace trace)
      throws ContractValidateException, AccountResourceInsufficientException, TooBigTransactionResultException {
    List<Contract> contracts = trx.getInstance().getRawData().getContractList();
    if (trx.getResultSerializedSize() > Constant.MAX_RESULT_SIZE_IN_TX * contracts.size()) {
      throw new TooBigTransactionResultException();
    }

    long bytesSize;
    if (dbManager.getDynamicPropertiesStore().supportVM()) {
      bytesSize = trx.getInstance().toBuilder().clearRet().build().getSerializedSize();
    } else {
      bytesSize = trx.getSerializedSize();
    }

    for (Contract contract : contracts) {
      if (dbManager.getDynamicPropertiesStore().supportVM()) {
        bytesSize += Constant.MAX_RESULT_SIZE_IN_TX;
      }

      logger.debug("trxId {},bandwidth cost :{}", trx.getTransactionId(), bytesSize);
      trace.setNetBill(bytesSize, 0);
      byte[] address = TransactionCapsule.getOwner(contract);
      AccountCapsule accountCapsule = dbManager.getAccountStore().get(address);
      if (accountCapsule == null) {
        throw new ContractValidateException("account not exists");
      }
      long now = dbManager.getWitnessController().getHeadSlot();

      if(contract.getType() == Contract.ContractType.TransferContract){
        continue;
      }

      if (contract.getType() == TransferAssetContract) {
        if (useAssetAccountNet(contract, accountCapsule, now, bytesSize)) {
          continue;
        }
      }

      if (useAccountNet(accountCapsule, bytesSize, now)) {
        continue;
      }

      //개인이 가지고 있는 NetUsage보유량을 우선적으로 사용하게 난 후 FreeNet 보유량을 소모하도록 함.
      if (useFreeNet(accountCapsule, bytesSize, now)) {
        continue;
      }

      if (useTransactionFee(accountCapsule, bytesSize, trace)) {
        continue;
      }

      // 만일 bandwidth 관련 수수료 정산이 정상적으로 이뤄지지 않으면 Exception을 발생시킴
      long fee = dbManager.getDynamicPropertiesStore().getTransactionFee() * bytesSize;
      throw new AccountResourceInsufficientException("Account Insufficient bandwidth[" + bytesSize + "] and balance[" + fee + "] to create new account");
    }
  }


  /**
   * Transaction 실행에 따른 수수료의 계산 및 수수료 지불 정보가 trace에서 관리됨.
   * @param accountCapsule
   * @param bytes
   * @param trace
   * @return
   */
  private boolean useTransactionFee(AccountCapsule accountCapsule, long bytes, TransactionTrace trace) {
    long fee = dbManager.getDynamicPropertiesStore().getTransactionFee() * bytes;
    if (consumeFee(accountCapsule, fee)) {
      trace.setNetBill(0, fee);
      dbManager.getDynamicPropertiesStore().addTotalTransactionCost(fee);
      return true;
    } else {
      return false;
    }
  }

  private void consumeForCreateNewAccount(AccountCapsule accountCapsule, long bytes,
      long now, TransactionTrace trace)
      throws AccountResourceInsufficientException {
    boolean ret = consumeBandwidthForCreateNewAccount(accountCapsule, bytes, now);

    if (!ret) {
      ret = consumeFeeForCreateNewAccount(accountCapsule, trace);
      if (!ret) {
        throw new AccountResourceInsufficientException();
      }
    }
  }

  public boolean consumeBandwidthForCreateNewAccount(AccountCapsule accountCapsule, long bytes, long now) {

    long createNewAccountBandwidthRatio = dbManager.getDynamicPropertiesStore()
        .getCreateNewAccountBandwidthRate();

    long netUsage = accountCapsule.getNetUsage();
    long latestConsumeTime = accountCapsule.getLatestConsumeTime();
    long netLimit = calculateGlobalNetLimit(accountCapsule);

    long newNetUsage = increase(netUsage, 0, latestConsumeTime, now);

    if (bytes * createNewAccountBandwidthRatio <= (netLimit - newNetUsage)) {
      latestConsumeTime = now;
      long latestOperationTime = dbManager.getHeadBlockTimeStamp();
      newNetUsage = increase(newNetUsage, bytes * createNewAccountBandwidthRatio, latestConsumeTime,
          now);
      accountCapsule.setLatestConsumeTime(latestConsumeTime);
      accountCapsule.setLatestOperationTime(latestOperationTime);
      accountCapsule.setNetUsage(newNetUsage);
      dbManager.getAccountStore().put(accountCapsule.createDbKey(), accountCapsule);
      return true;
    }
    return false;
  }

  public boolean consumeFeeForCreateNewAccount(AccountCapsule accountCapsule,
      TransactionTrace trace) {
    long fee = dbManager.getDynamicPropertiesStore().getCreateAccountFee();
    if (consumeFee(accountCapsule, fee)) {
      trace.setNetBill(0, fee);
      dbManager.getDynamicPropertiesStore().addTotalCreateAccountCost(fee);
      return true;
    } else {
      return false;
    }
  }

  public boolean contractCreateNewAccount(Contract contract) {
    AccountCapsule toAccount;
    switch (contract.getType()) {
      case AccountCreateContract:
        return true;
      case TransferContract:
        TransferContract transferContract;
        try {
          transferContract = contract.getParameter().unpack(TransferContract.class);
        } catch (Exception ex) {
          throw new RuntimeException(ex.getMessage());
        }
        toAccount = dbManager.getAccountStore().get(transferContract.getToAddress().toByteArray());
        return toAccount == null;
      case TransferAssetContract:
        TransferAssetContract transferAssetContract;
        try {
          transferAssetContract = contract.getParameter().unpack(TransferAssetContract.class);
        } catch (Exception ex) {
          throw new RuntimeException(ex.getMessage());
        }
        toAccount = dbManager.getAccountStore()
            .get(transferAssetContract.getToAddress().toByteArray());
        return toAccount == null;
      default:
        return false;
    }
  }


  /**
   * bytes 와 (publicFreeAssetNetLimit - newPublicFreeAssetNetUsage) 의 비교 <br/>
   * bytes 와 (freeAssetNetLimit - newFreeAssetNetUsage) 의 비교 <br/>
   * bytes 와 (issuerNetLimit - newIssuerNetUsage) 의 비교를 거친 후 Account에 변경된 asset 정보를 입력한 후 true를 반환함.
   *
   * @param contract
   * @param accountCapsule
   * @param now
   * @param bytes
   * @return
   * @throws ContractValidateException
   */
  private boolean useAssetAccountNet(Contract contract, AccountCapsule accountCapsule, long now, long bytes)
      throws ContractValidateException {

    ByteString assetName;
    try {
      assetName = contract.getParameter().unpack(TransferAssetContract.class).getAssetName();
    } catch (Exception ex) {
      throw new RuntimeException(ex.getMessage());
    }

    AssetIssueCapsule assetIssueCapsule, assetIssueCapsuleV2;
    assetIssueCapsule = dbManager.getAssetIssueStoreFinal().get(assetName.toByteArray());
    if (assetIssueCapsule == null) {
      throw new ContractValidateException("asset not exists");
    }

    String tokenName = ByteArray.toStr(assetName.toByteArray());
    String tokenID = assetIssueCapsule.getId();
    // TransferAsset의 행위주체가 Asset의 owner인 경우에 Asset이 아닌 AccountNet을 이용하여 비용을 지불하게 됨.
    if (assetIssueCapsule.getOwnerAddress() == accountCapsule.getAddress()) {
      return useAccountNet(accountCapsule, bytes, now);
    }

    // TODO public의 의미가 어떤 의미인가???
    long publicFreeAssetNetLimit = assetIssueCapsule.getPublicFreeAssetNetLimit();
    long publicFreeAssetNetUsage = assetIssueCapsule.getPublicFreeAssetNetUsage();
    long publicLatestFreeNetTime = assetIssueCapsule.getPublicLatestFreeNetTime();

    long newPublicFreeAssetNetUsage = increase(publicFreeAssetNetUsage, 0, publicLatestFreeNetTime, now);

    if (bytes > (publicFreeAssetNetLimit - newPublicFreeAssetNetUsage)) {
      logger.debug("The " + tokenID + " public free bandwidth is not enough");
      return false;
    }

    long freeAssetNetLimit = assetIssueCapsule.getFreeAssetNetLimit();

    long freeAssetNetUsage, latestAssetOperationTime;
    if (dbManager.getDynamicPropertiesStore().getAllowSameTokenName() == 0) {
      freeAssetNetUsage = accountCapsule.getFreeAssetNetUsage(tokenName);
      latestAssetOperationTime = accountCapsule.getLatestAssetOperationTime(tokenName);
    } else {
      freeAssetNetUsage = accountCapsule.getFreeAssetNetUsageV2(tokenID);
      latestAssetOperationTime = accountCapsule.getLatestAssetOperationTimeV2(tokenID);
    }

    long newFreeAssetNetUsage = increase(freeAssetNetUsage, 0,latestAssetOperationTime, now);

    if (bytes > (freeAssetNetLimit - newFreeAssetNetUsage)) {
      logger.debug("The " + tokenID + " free bandwidth is not enough");
      return false;
    }

    AccountCapsule issuerAccountCapsule = dbManager.getAccountStore().get(assetIssueCapsule.getOwnerAddress().toByteArray());

    long issuerNetUsage = issuerAccountCapsule.getNetUsage();
    long latestConsumeTime = issuerAccountCapsule.getLatestConsumeTime();
    long issuerNetLimit = calculateGlobalNetLimit(issuerAccountCapsule);

    long newIssuerNetUsage = increase(issuerNetUsage, 0, latestConsumeTime, now);

    if (bytes > (issuerNetLimit - newIssuerNetUsage)) {
      logger.debug("The " + tokenID + " issuer'bandwidth is not enough");
      return false;
    }

    latestConsumeTime = now;
    latestAssetOperationTime = now;
    publicLatestFreeNetTime = now;
    long latestOperationTime = dbManager.getHeadBlockTimeStamp();

    newIssuerNetUsage = increase(newIssuerNetUsage, bytes, latestConsumeTime, now);
    newFreeAssetNetUsage = increase(newFreeAssetNetUsage,bytes, latestAssetOperationTime, now);
    newPublicFreeAssetNetUsage = increase(newPublicFreeAssetNetUsage, bytes, publicLatestFreeNetTime, now);

    issuerAccountCapsule.setNetUsage(newIssuerNetUsage);
    issuerAccountCapsule.setLatestConsumeTime(latestConsumeTime);

    assetIssueCapsule.setPublicFreeAssetNetUsage(newPublicFreeAssetNetUsage);
    assetIssueCapsule.setPublicLatestFreeNetTime(publicLatestFreeNetTime);

    accountCapsule.setLatestOperationTime(latestOperationTime);
    if (dbManager.getDynamicPropertiesStore().getAllowSameTokenName() == 0) {
      accountCapsule.putLatestAssetOperationTimeMap(tokenName, latestAssetOperationTime);
      accountCapsule.putFreeAssetNetUsage(tokenName, newFreeAssetNetUsage);
      accountCapsule.putLatestAssetOperationTimeMapV2(tokenID, latestAssetOperationTime);
      accountCapsule.putFreeAssetNetUsageV2(tokenID, newFreeAssetNetUsage);

      dbManager.getAssetIssueStore().put(assetIssueCapsule.createDbKey(), assetIssueCapsule);

      assetIssueCapsuleV2 = dbManager.getAssetIssueV2Store().get(assetIssueCapsule.createDbV2Key());
      assetIssueCapsuleV2.setPublicFreeAssetNetUsage(newPublicFreeAssetNetUsage);
      assetIssueCapsuleV2.setPublicLatestFreeNetTime(publicLatestFreeNetTime);
      dbManager.getAssetIssueV2Store().put(assetIssueCapsuleV2.createDbV2Key(), assetIssueCapsuleV2);
    } else {
      accountCapsule.putLatestAssetOperationTimeMapV2(tokenID, latestAssetOperationTime);
      accountCapsule.putFreeAssetNetUsageV2(tokenID, newFreeAssetNetUsage);
      dbManager.getAssetIssueV2Store().put(assetIssueCapsule.createDbV2Key(), assetIssueCapsule);
    }

    dbManager.getAccountStore().put(accountCapsule.createDbKey(), accountCapsule);
    dbManager.getAccountStore().put(issuerAccountCapsule.createDbKey(), issuerAccountCapsule);

    return true;

  }

  public long calculateGlobalNetLimit(AccountCapsule accountCapsule) {
    long frozeBalance = accountCapsule.getAllFrozenBalanceForBandwidth();
    if (frozeBalance < 1000_000L) {
      return 0;
    }
    long netWeight = frozeBalance / 1000_000L;
    long totalNetLimit = dbManager.getDynamicPropertiesStore().getTotalNetLimit();
    long totalNetWeight = dbManager.getDynamicPropertiesStore().getTotalNetWeight();
    if (totalNetWeight == 0) {
      return 0;
    }
    return (long) (netWeight * ((double) totalNetLimit / totalNetWeight));
  }

  /**
   * bytes 와 (netLimit - newNetUsage) 의 비교를 거친 후 Account에 변경된 net usage 및 실행 정보를 갱신한 후 true를 반환함.
   *
   * @param accountCapsule
   * @param bytes
   * @param now
   * @return
   */
  private boolean useAccountNet(AccountCapsule accountCapsule, long bytes, long now) {

    long netUsage = accountCapsule.getNetUsage();
    long latestConsumeTime = accountCapsule.getLatestConsumeTime();
    long netLimit = calculateGlobalNetLimit(accountCapsule);

    long newNetUsage = increase(netUsage, 0, latestConsumeTime, now);

    if (bytes > (netLimit - newNetUsage)) {
      logger.debug("net usage is running out. now use free net usage");
      return false;
    }

    latestConsumeTime = now;
    long latestOperationTime = dbManager.getHeadBlockTimeStamp();
    newNetUsage = increase(newNetUsage, bytes, latestConsumeTime, now);
    accountCapsule.setNetUsage(newNetUsage);
    accountCapsule.setLatestOperationTime(latestOperationTime);
    accountCapsule.setLatestConsumeTime(latestConsumeTime);

    dbManager.getAccountStore().put(accountCapsule.createDbKey(), accountCapsule);
    return true;
  }
  private boolean useFreeNet(AccountCapsule accountCapsule, long bytes, long now) {

    long freeNetLimit = dbManager.getDynamicPropertiesStore().getFreeNetLimit();
    long freeNetUsage = accountCapsule.getFreeNetUsage();
    long latestConsumeFreeTime = accountCapsule.getLatestConsumeFreeTime();
    long newFreeNetUsage = increase(freeNetUsage, 0, latestConsumeFreeTime, now);

    if (bytes > (freeNetLimit - newFreeNetUsage)) {
      logger.debug("free net usage is running out");
      return false;
    }

    long publicNetLimit = dbManager.getDynamicPropertiesStore().getPublicNetLimit();
    long publicNetUsage = dbManager.getDynamicPropertiesStore().getPublicNetUsage();
    long publicNetTime = dbManager.getDynamicPropertiesStore().getPublicNetTime();

    long newPublicNetUsage = increase(publicNetUsage, 0, publicNetTime, now);

    if (bytes > (publicNetLimit - newPublicNetUsage)) {
      logger.debug("free public net usage is running out");
      return false;
    }

    latestConsumeFreeTime = now;
    long latestOperationTime = dbManager.getHeadBlockTimeStamp();
    publicNetTime = now;
    newFreeNetUsage = increase(newFreeNetUsage, bytes, latestConsumeFreeTime, now);
    newPublicNetUsage = increase(newPublicNetUsage, bytes, publicNetTime, now);
    accountCapsule.setFreeNetUsage(newFreeNetUsage);
    accountCapsule.setLatestConsumeFreeTime(latestConsumeFreeTime);
    accountCapsule.setLatestOperationTime(latestOperationTime);

    dbManager.getDynamicPropertiesStore().savePublicNetUsage(newPublicNetUsage);
    dbManager.getDynamicPropertiesStore().savePublicNetTime(publicNetTime);
    dbManager.getAccountStore().put(accountCapsule.createDbKey(), accountCapsule);
    return true;

  }

}


