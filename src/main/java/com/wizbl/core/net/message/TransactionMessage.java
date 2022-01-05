package com.wizbl.core.net.message;

import com.wizbl.common.utils.Sha256Hash;
import com.wizbl.core.capsule.TransactionCapsule;
import com.wizbl.core.exception.BadItemException;
import com.wizbl.protos.Protocol.Transaction;

public class TransactionMessage extends Brte2Message {

  private TransactionCapsule transactionCapsule;

  public TransactionMessage(byte[] data) throws BadItemException {
    this.transactionCapsule = new TransactionCapsule(data);
    this.data = data;
    this.type = MessageTypes.TRX.asByte();
  }

  public TransactionMessage(Transaction trx) {
    this.transactionCapsule = new TransactionCapsule(trx);
    this.type = MessageTypes.TRX.asByte();
    this.data = trx.toByteArray();
  }

  @Override
  public String toString() {
    return new StringBuilder().append(super.toString())
        .append("messageId: ").append(super.getMessageId()).toString();
  }

  @Override
  public Sha256Hash getMessageId() {
    return this.transactionCapsule.getTransactionId();
  }

  @Override
  public Class<?> getAnswerMessage() {
    return null;
  }

  public TransactionCapsule getTransactionCapsule() {
    return this.transactionCapsule;
  }
}
