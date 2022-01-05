package com.wizbl.core.exception;

public class AccountResourceInsufficientException extends Brte2Exception {

  public AccountResourceInsufficientException() {
    super("Insufficient bandwidth and balance to create new account");
  }

  public AccountResourceInsufficientException(String message) {
    super(message);
  }
}

