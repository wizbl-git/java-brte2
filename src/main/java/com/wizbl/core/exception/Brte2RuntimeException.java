package com.wizbl.core.exception;

public class Brte2RuntimeException extends RuntimeException {

  public Brte2RuntimeException() {
    super();
  }

  public Brte2RuntimeException(String message) {
    super(message);
  }

  public Brte2RuntimeException(String message, Throwable cause) {
    super(message, cause);
  }

  public Brte2RuntimeException(Throwable cause) {
    super(cause);
  }

  protected Brte2RuntimeException(String message, Throwable cause,
                                  boolean enableSuppression,
                                  boolean writableStackTrace) {
    super(message, cause, enableSuppression, writableStackTrace);
  }


}
