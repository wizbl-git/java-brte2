package com.wizbl.core.db;

import com.google.common.collect.Iterators;
import com.google.common.collect.Maps;
import com.google.common.collect.Streams;
import com.google.common.reflect.TypeToken;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import javax.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import com.wizbl.core.capsule.ProtoCapsule;
import com.wizbl.core.config.args.Args;
import com.wizbl.core.db.api.IndexHelper;
import com.wizbl.core.db2.common.IRevokingDB;
import com.wizbl.core.db2.core.IBrte2ChainBase;
import com.wizbl.core.db2.core.RevokingDBWithCachingNewValue;
import com.wizbl.core.db2.core.RevokingDBWithCachingOldValue;
import com.wizbl.core.exception.BadItemException;
import com.wizbl.core.exception.ItemNotFoundException;

@Slf4j
public abstract class Brte2StoreWithRevoking<T extends ProtoCapsule> implements IBrte2ChainBase<T> {
  @Getter // only for unit test
  protected IRevokingDB revokingDB;
  private TypeToken<T> token = new TypeToken<T>(getClass()) {};
  @Autowired
  private RevokingDatabase revokingDatabase;
  @Autowired(required = false)
  protected IndexHelper indexHelper;
  @Getter
  private String dbName;

  /**
   * Brte2StoreWithRevoking 클래스를 확장한(extends) 클래스에서 객체 생성 시 부모클래스의 생성자를 호출하여
   * IRevokingDB 구현 객체를 생성함.
   * @param dbName
   */
  protected Brte2StoreWithRevoking(String dbName) {
    this.dbName = dbName;
    int dbVersion = Args.getInstance().getStorage().getDbVersion();
    if (dbVersion == 1) {
      this.revokingDB = new RevokingDBWithCachingOldValue(dbName);
    } else if (dbVersion == 2) {
      this.revokingDB = new RevokingDBWithCachingNewValue(dbName);
    } else {
      throw new RuntimeException("db version is error.");
    }
  }

  /**
   * 초기화 과정에서 생성된 IRevokingDB 구현 객체는 RevokingDatabase 구현 객체의 add 메소드를 참조함. <br/>
   * 이 때 add 메소드는 SnapshotManager 객체의 add 메소드를 참조함.
   */
  @PostConstruct
  private void init() {
    revokingDatabase.add(revokingDB);
  }

  // only for test
  protected Brte2StoreWithRevoking(String dbName, RevokingDatabase revokingDatabase) {
      this.revokingDB = new RevokingDBWithCachingOldValue(dbName, (AbstractRevokingStore) revokingDatabase);
  }

  @Override
  public void put(byte[] key, T item) {
    if (Objects.isNull(key) || Objects.isNull(item)) {
      return;
    }

    revokingDB.put(key, item.getData());
  }

  @Override
  public void delete(byte[] key) {
    revokingDB.delete(key);
  }

  @Override
  public T get(byte[] key) throws ItemNotFoundException, BadItemException {
    return of(revokingDB.get(key));
  }

  @Override
  public T getUnchecked(byte[] key) {
    byte[] value = revokingDB.getUnchecked(key);

    try {
      return of(value);
    } catch (BadItemException e) {
      return null;
    }
  }

  public T of(byte[] value) throws BadItemException {
    try {
      Constructor constructor = token.getRawType().getConstructor(byte[].class);
      @SuppressWarnings("unchecked")
      T t = (T) constructor.newInstance((Object) value);
      return t;
    } catch (NoSuchMethodException | IllegalAccessException | InstantiationException | InvocationTargetException e) {
      throw new BadItemException(e.getMessage());
    }
  }

  @Override
  public boolean has(byte[] key) {
    return revokingDB.has(key);
  }

  @Override
  public String getName() {
    return getClass().getSimpleName();
  }

  @Override
  public void close() {
    revokingDB.close();
  }

  @Override
  public void reset() {
    revokingDB.reset();
  }

  @Override
  public Iterator<Map.Entry<byte[], T>> iterator() {
    return Iterators.transform(revokingDB.iterator(), e -> {
      try {
        return Maps.immutableEntry(e.getKey(), of(e.getValue()));
      } catch (BadItemException e1) {
        throw new RuntimeException(e1);
      }
    });
  }

  public long size() {
    return Streams.stream(revokingDB.iterator()).count();
  }

  public void setMode(boolean mode) {
    revokingDB.setMode(mode);
  }

}
