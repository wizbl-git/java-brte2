package com.wizbl.core.db;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.wizbl.core.capsule.BytesCapsule;
import com.wizbl.core.exception.ItemNotFoundException;

@Component
public class RecentBlockStore extends Brte2StoreWithRevoking<BytesCapsule> {

  @Autowired
  private RecentBlockStore(@Value("recent-block") String dbName) {
    super(dbName);
  }

  @Override
  public BytesCapsule get(byte[] key) throws ItemNotFoundException {
    byte[] value = revokingDB.get(key);

    return new BytesCapsule(value);
  }
}
