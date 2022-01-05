package com.wizbl.core.actuator;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.wizbl.core.capsule.TransactionResultCapsule;
import com.wizbl.core.exception.ContractExeException;
import com.wizbl.core.exception.ContractValidateException;

public interface Actuator {

  boolean execute(TransactionResultCapsule result) throws ContractExeException;

  boolean validate() throws ContractValidateException;

  ByteString getOwnerAddress() throws InvalidProtocolBufferException;

  long calcFee();

}
