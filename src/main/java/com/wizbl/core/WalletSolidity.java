package com.wizbl.core;

import com.google.protobuf.ByteString;
import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.wizbl.api.GrpcAPI.TransactionList;
import com.wizbl.common.utils.ByteArray;
import com.wizbl.core.db.api.StoreAPI;
import com.wizbl.protos.Protocol.Transaction;

@Slf4j
@Component
public class WalletSolidity {

  @Autowired
  private StoreAPI storeAPI;

  public TransactionList getTransactionsFromThis(ByteString thisAddress, long offset, long limit) {
    List<Transaction> transactionsFromThis = storeAPI
        .getTransactionsFromThis(ByteArray.toHexString(thisAddress.toByteArray()), offset, limit);
    TransactionList transactionList = TransactionList.newBuilder()
        .addAllTransaction(transactionsFromThis).build();
    return transactionList;
  }

  public TransactionList getTransactionsToThis(ByteString toAddress, long offset, long limit) {
    List<Transaction> transactionsToThis = storeAPI
        .getTransactionsToThis(ByteArray.toHexString(toAddress.toByteArray()), offset, limit);
    TransactionList transactionList = TransactionList.newBuilder()
        .addAllTransaction(transactionsToThis).build();
    return transactionList;
  }
}
