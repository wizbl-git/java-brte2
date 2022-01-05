package com.wizbl.core.exception;

public class VMMemoryOverflowException extends Brte2Exception {

  public VMMemoryOverflowException() {
    super("VM memory overflow");
  }

  public VMMemoryOverflowException(String message) {
    super(message);
  }

}
