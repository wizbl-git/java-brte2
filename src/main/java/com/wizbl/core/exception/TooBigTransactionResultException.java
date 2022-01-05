package com.wizbl.core.exception;

public class TooBigTransactionResultException extends Brte2Exception {

    public TooBigTransactionResultException() { super("too big transaction result"); }

    public TooBigTransactionResultException(String message) { super(message); }
}
