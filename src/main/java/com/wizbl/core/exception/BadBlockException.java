package com.wizbl.core.exception;

/**
 * 유효하지 않은 Block인 경우에 발생하는 예외 <br/>
 * signature가 일치하지 않거나, merkleRoot의 값이 올바르지 않은 경우에 발생함.
 */
public class BadBlockException extends Brte2Exception {

  public BadBlockException() {
    super();
  }

  public BadBlockException(String message) {
    super(message);
  }
}
