package com.wizbl.common.runtime;

import com.wizbl.common.runtime.vm.program.InternalTransaction.TrxType;
import com.wizbl.common.runtime.vm.program.ProgramResult;
import com.wizbl.core.exception.ContractExeException;
import com.wizbl.core.exception.ContractValidateException;
import com.wizbl.core.exception.VMIllegalException;


public interface Runtime {

  boolean isCallConstant() throws ContractValidateException;

  void execute() throws ContractValidateException, ContractExeException, VMIllegalException;

  void go();

  TrxType getTrxType();

  void finalization();

  ProgramResult getResult();

  String getRuntimeError();
}
