package com.wizbl.core.actuator;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import com.wizbl.common.utils.StringUtil;
import com.wizbl.core.Wallet;
import com.wizbl.core.capsule.AccountCapsule;
import com.wizbl.core.capsule.TransactionResultCapsule;
import com.wizbl.core.db.Manager;
import com.wizbl.core.db.StorageMarket;
import com.wizbl.core.exception.ContractExeException;
import com.wizbl.core.exception.ContractValidateException;
import com.wizbl.protos.Contract.BuyStorageBytesContract;
import com.wizbl.protos.Protocol.Transaction.Result.code;

@Slf4j
public class BuyStorageBytesActuator extends AbstractActuator {

  private StorageMarket storageMarket;

  BuyStorageBytesActuator(Any contract, Manager dbManager) {
    super(contract, dbManager);
    storageMarket = new StorageMarket(dbManager);
  }

  @Override
  public boolean execute(TransactionResultCapsule ret) throws ContractExeException {
    long fee = calcFee();
    final BuyStorageBytesContract BuyStorageBytesContract;
    try {
      BuyStorageBytesContract = contract.unpack(BuyStorageBytesContract.class);
    } catch (InvalidProtocolBufferException e) {
      logger.debug(e.getMessage(), e);
      ret.setStatus(fee, code.FAILED);
      throw new ContractExeException(e.getMessage());
    }

    AccountCapsule accountCapsule = dbManager.getAccountStore()
        .get(BuyStorageBytesContract.getOwnerAddress().toByteArray());
    long bytes = BuyStorageBytesContract.getBytes();

    storageMarket.buyStorageBytes(accountCapsule, bytes);

    ret.setStatus(fee, code.SUCESS);

    return true;
  }


  @Override
  public boolean validate() throws ContractValidateException {
    if (this.contract == null) {
      throw new ContractValidateException("No contract!");
    }
    if (this.dbManager == null) {
      throw new ContractValidateException("No dbManager!");
    }
    if (!contract.is(BuyStorageBytesContract.class)) {
      throw new ContractValidateException(
          "contract type error,expected type [BuyStorageBytesContract],real type[" + contract
              .getClass() + "]");
    }

    final BuyStorageBytesContract BuyStorageBytesContract;
    try {
      BuyStorageBytesContract = this.contract.unpack(BuyStorageBytesContract.class);
    } catch (InvalidProtocolBufferException e) {
      logger.debug(e.getMessage(), e);
      throw new ContractValidateException(e.getMessage());
    }
    byte[] ownerAddress = BuyStorageBytesContract.getOwnerAddress().toByteArray();
    if (!Wallet.addressValid(ownerAddress)) {
      throw new ContractValidateException("Invalid address");
    }

    AccountCapsule accountCapsule = dbManager.getAccountStore().get(ownerAddress);
    if (accountCapsule == null) {
      String readableOwnerAddress = StringUtil.createReadableString(ownerAddress);
      throw new ContractValidateException(
          "Account[" + readableOwnerAddress + "] not exists");
    }

    long bytes = BuyStorageBytesContract.getBytes();
    if (bytes < 0) {
      throw new ContractValidateException("bytes must be positive");
    }

    if (bytes < 1L) {
      throw new ContractValidateException(
          "bytes must be larger than 1, current storage_bytes[" + bytes + "]");
    }

    long quant = storageMarket.tryBuyStorageBytes(bytes);

    if (quant < 100_000_000L) {
      throw new ContractValidateException("quantity must be larger than 1WBL");
    }

    if (quant > accountCapsule.getBalance()) {
      throw new ContractValidateException("quantity must be less than accountBalance");
    }

//    long storageBytes = storageMarket.exchange(quant, true);
//    if (storageBytes > dbManager.getDynamicPropertiesStore().getTotalStorageReserved()) {
//      throw new ContractValidateException("storage is not enough");
//    }

    return true;
  }

  @Override
  public ByteString getOwnerAddress() throws InvalidProtocolBufferException {
    return contract.unpack(BuyStorageBytesContract.class).getOwnerAddress();
  }

  @Override
  public long calcFee() {
    return 0;
  }

}
