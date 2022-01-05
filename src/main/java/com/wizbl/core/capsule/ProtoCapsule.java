package com.wizbl.core.capsule;

public interface ProtoCapsule<T> {

  byte[] getData();

  T getInstance();
}
