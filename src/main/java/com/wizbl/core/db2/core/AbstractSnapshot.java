package com.wizbl.core.db2.core;

import java.lang.ref.WeakReference;
import lombok.Getter;
import lombok.Setter;
import com.wizbl.core.db2.common.DB;

public abstract class AbstractSnapshot<K, V> implements Snapshot {
  @Getter
  protected DB<K, V> db;
  @Getter
  @Setter
  protected Snapshot previous;

  protected WeakReference<Snapshot> next;

  /**
   * SnapshotImpl 객체를 새로 생성해서 반환함.
   * @return
   */
  @Override
  public Snapshot advance() {
    return new SnapshotImpl(this);
  }

  @Override
  public Snapshot getNext() {
    return next == null ? null : next.get();
  }

  @Override
  public void setNext(Snapshot next) {
    this.next = new WeakReference<>(next);
  }
}
