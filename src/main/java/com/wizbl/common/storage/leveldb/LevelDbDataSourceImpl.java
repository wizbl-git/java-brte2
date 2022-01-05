/*
 * Copyright (c) [2016] [ <ether.camp> ] This file is part of the ethereumJ library.
 *
 * The ethereumJ library is free software: you can redistribute it and/or modify it under the terms
 * of the GNU Lesser General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * The ethereumJ library is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
 * PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with the ethereumJ
 * library. If not, see <http://www.gnu.org/licenses/>.
 */

package com.wizbl.common.storage.leveldb;

import static org.fusesource.leveldbjni.JniDBFactory.factory;

import com.google.common.collect.Sets;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.iq80.leveldb.CompressionType;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBException;
import org.iq80.leveldb.DBIterator;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.WriteBatch;
import org.iq80.leveldb.WriteOptions;
import com.wizbl.common.storage.DbSourceInter;
import com.wizbl.common.utils.FileUtil;
import com.wizbl.core.config.args.Args;
import com.wizbl.core.db.common.iterator.StoreIterator;

/**
 * LevelDbDataSourceImpl 클래스는 LevelDB를 사용하는데 있어서 필요로 하는 메소드를 구현해 놓은 클래스임.
 * Init DB, Open DB, Data의 put, get, delete 를 비롯한 LevelDB에서 관리하는 Key, Value를 제어하기 위한 메소드도 구현되어 있음.
 */
@Slf4j
@NoArgsConstructor
public class LevelDbDataSourceImpl implements DbSourceInter<byte[]>,
    Iterable<Map.Entry<byte[], byte[]>> {

  String dataBaseName;
  DB database;
  boolean alive;
  private String parentName;
  private ReadWriteLock resetDbLock = new ReentrantReadWriteLock();

  /**
   * constructor.
   */
  public LevelDbDataSourceImpl(String parentName, String name) {
    this.dataBaseName = name;
    this.parentName = Paths.get(parentName, Args.getInstance().getStorage().getDbDirectory()).toString();
  }

  /**
   * DB를 초기화하는 메소드 <br/>
   * DB를 Open하기 전에 DB에 WriteLock을 건 후 database의 open 및 alive = true 상태로 초기화함. <br/>
   * DB Open이 완료되면 WriteLock을 걸었던 DB에 Lock을 해제함.
   */
  @Override
  public void initDB() {
    resetDbLock.writeLock().lock();
    try {
      logger.debug("~> LevelDbDataSourceImpl.initDB(): " + dataBaseName);

      if (isAlive()) {
        return;
      }

      if (dataBaseName == null) {
        throw new NullPointerException("no name set to the dbStore");
      }

      Options dbOptions = Args.getInstance().getStorage().getOptionsByDbName(dataBaseName);

      try {
        openDatabase(dbOptions);
        alive = true;
      } catch (IOException ioe) {
        throw new RuntimeException("Can't initialize database", ioe);
      }
    } finally {
      resetDbLock.writeLock().unlock();
    }
  }

  /**
   * levelDB를 dbPath 및 dbOption 설정에 맞춰서 open함.
   * @param dbOptions
   * @throws IOException
   */
  private void openDatabase(Options dbOptions) throws IOException {
    final Path dbPath = getDbPath();
    if (!Files.isSymbolicLink(dbPath.getParent())) {
      Files.createDirectories(dbPath.getParent());
    }
    try {
      database = factory.open(dbPath.toFile(), dbOptions);
    } catch (IOException e) {
      if (e.getMessage().contains("Corruption:")) {
        factory.repair(dbPath.toFile(), dbOptions);
        database = factory.open(dbPath.toFile(), dbOptions);
      } else {
        throw e;
      }
    }
  }

  @Deprecated
  private Options createDbOptions() {
    Options dbOptions = new Options();
    dbOptions.createIfMissing(true);
    dbOptions.compressionType(CompressionType.NONE);
    dbOptions.blockSize(10 * 1024 * 1024);
    dbOptions.writeBufferSize(10 * 1024 * 1024);
    dbOptions.cacheSize(0);
    dbOptions.paranoidChecks(true);
    dbOptions.verifyChecksums(true);
    dbOptions.maxOpenFiles(32);
    return dbOptions;
  }

  public Path getDbPath() {
    return Paths.get(parentName, dataBaseName);
  }

  /**
   * reset database.
   */
  public void resetDb() {
    closeDB();
    FileUtil.recursiveDelete(getDbPath().toString());
    initDB();
  }

  public void reOpen() {
    resetDbLock.writeLock().lock();
    try {
      closeDB();
      initDB();
    } finally {
      resetDbLock.writeLock().unlock();
    }
  }

  @Override
  public boolean isAlive() {
    return alive;
  }

  /**
   * destroy database.
   */
  public void destroyDb(File fileLocation) {
    resetDbLock.writeLock().lock();
    try {
      logger.debug("Destroying existing database: " + fileLocation);
      Options options = new Options();
      try {
        factory.destroy(fileLocation, options);
      } catch (IOException e) {
        logger.error(e.getMessage(), e);
      }
    } finally {
      resetDbLock.writeLock().unlock();
    }
  }

  @Override
  public String getDBName() {
    return dataBaseName;
  }

  @Override
  public void setDBName(String name) {
    this.dataBaseName = name;
  }

  @Override
  public byte[] getData(byte[] key) {
    resetDbLock.readLock().lock();
    try {
      return database.get(key);
    } catch (DBException e) {
      logger.debug(e.getMessage(), e);
    } finally {
      resetDbLock.readLock().unlock();
    }
    return null;
  }

  //TODO putData를 하는데 왜 writeLock을 사용하지 않고 readLock을 사용하는 것이지???
  @Override
  public void putData(byte[] key, byte[] value) {
    resetDbLock.readLock().lock();
    try {
      database.put(key, value);
    } finally {
      resetDbLock.readLock().unlock();
    }
  }

  //TODO putData를 하는데 왜 writeLock을 사용하지 않고 readLock을 사용하는 것이지???
  @Override
  public void putData(byte[] key, byte[] value, WriteOptions options) {
    resetDbLock.readLock().lock();
    try {
      database.put(key, value, options);
    } finally {
      resetDbLock.readLock().unlock();
    }
  }

  //TODO deleteData를 하는데 왜 writeLock을 사용하지 않고 readLock을 사용하는 것이지???
  @Override
  public void deleteData(byte[] key) {
    resetDbLock.readLock().lock();
    try {
      database.delete(key);
    } finally {
      resetDbLock.readLock().unlock();
    }
  }

  //TODO deleteData를 하는데 왜 writeLock을 사용하지 않고 readLock을 사용하는 것이지???
  @Override
  public void deleteData(byte[] key, WriteOptions options) {
    resetDbLock.readLock().lock();
    try {
      database.delete(key, options);
    } finally {
      resetDbLock.readLock().unlock();
    }
  }

  @Deprecated
  @Override
  public Set<byte[]> allKeys() {
    resetDbLock.readLock().lock();
    try (DBIterator iterator = database.iterator()) {
      Set<byte[]> result = Sets.newHashSet();
      for (iterator.seekToFirst(); iterator.hasNext(); iterator.next()) {
        result.add(iterator.peekNext().getKey());
      }
      return result;
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      resetDbLock.readLock().unlock();
    }
  }

  @Deprecated
  @Override
  public Set<byte[]> allValues() {
    resetDbLock.readLock().lock();
    try (DBIterator iterator = database.iterator()) {
      Set<byte[]> result = Sets.newHashSet();
      for (iterator.seekToFirst(); iterator.hasNext(); iterator.next()) {
        result.add(iterator.peekNext().getValue());
      }
      return result;
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      resetDbLock.readLock().unlock();
    }
  }

  public Set<byte[]> getlatestValues(long limit) {
    if (limit <= 0) {
      return Sets.newHashSet();
    }
    resetDbLock.readLock().lock();
    try (DBIterator iterator = database.iterator()) {
      Set<byte[]> result = Sets.newHashSet();
      long i = 0;
      iterator.seekToLast();
      if (iterator.hasNext()) {
        result.add(iterator.peekNext().getValue());
        i++;
      }
      for (; iterator.hasPrev() && i++ < limit; iterator.prev()) {
        result.add(iterator.peekPrev().getValue());
      }
      return result;
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      resetDbLock.readLock().unlock();
    }
  }

  public Set<byte[]> getValuesNext(byte[] key, long limit) {
    if (limit <= 0) {
      return Sets.newHashSet();
    }
    resetDbLock.readLock().lock();
    try (DBIterator iterator = database.iterator()) {
      Set<byte[]> result = Sets.newHashSet();
      long i = 0;
      for (iterator.seek(key); iterator.hasNext() && i++ < limit; iterator.next()) {
        result.add(iterator.peekNext().getValue());
      }
      return result;
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      resetDbLock.readLock().unlock();
    }
  }

  public Map<byte[], byte[]> getNext(byte[] key, long limit) {
    if (limit <= 0) {
      return Collections.emptyMap();
    }
    resetDbLock.readLock().lock();
    try (DBIterator iterator = database.iterator()) {
      Map<byte[], byte[]> result = new HashMap<>();
      long i = 0;
      for (iterator.seek(key); iterator.hasNext() && i++ < limit; iterator.next()) {
        Entry<byte[], byte[]> entry = iterator.peekNext();
        result.put(entry.getKey(), entry.getValue());
      }
      return result;
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      resetDbLock.readLock().unlock();
    }
  }

  public Set<byte[]> getValuesPrev(byte[] key, long limit) {
    if (limit <= 0) {
      return Sets.newHashSet();
    }
    resetDbLock.readLock().lock();
    try (DBIterator iterator = database.iterator()) {
      Set<byte[]> result = Sets.newHashSet();
      long i = 0;
      byte[] data = getData(key);
      if (Objects.nonNull(data)) {
        result.add(data);
        i++;
      }
      for (iterator.seek(key); iterator.hasPrev() && i++ < limit; iterator.prev()) {
        result.add(iterator.peekPrev().getValue());
      }
      return result;
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      resetDbLock.readLock().unlock();
    }
  }

  @Override
  public long getTotal() throws RuntimeException {
    resetDbLock.readLock().lock();
    try (DBIterator iterator = database.iterator()) {
      long total = 0;
      for (iterator.seekToFirst(); iterator.hasNext(); iterator.next()) {
        total++;
      }
      return total;
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      resetDbLock.readLock().unlock();
    }
  }

  private void updateByBatchInner(Map<byte[], byte[]> rows) throws Exception {
    try (WriteBatch batch = database.createWriteBatch()) {
      rows.forEach((key, value) -> {
        if (value == null) {
          batch.delete(key);
        } else {
          batch.put(key, value);
        }
      });
      database.write(batch);
    }
  }

  private void updateByBatchInner(Map<byte[], byte[]> rows, WriteOptions options) throws Exception {
    try (WriteBatch batch = database.createWriteBatch()) {
      rows.forEach((key, value) -> {
        if (value == null) {
          batch.delete(key);
        } else {
          batch.put(key, value);
        }
      });
      database.write(batch, options);
    }
  }

  @Override
  public void updateByBatch(Map<byte[], byte[]> rows) {
    resetDbLock.readLock().lock();
    try {
      updateByBatchInner(rows);
    } catch (Exception e) {
      try {
        updateByBatchInner(rows);
      } catch (Exception e1) {
        throw new RuntimeException(e);
      }
    } finally {
      resetDbLock.readLock().unlock();
    }
  }

  @Override
  public void updateByBatch(Map<byte[], byte[]> rows, WriteOptions options) {
    resetDbLock.readLock().lock();
    try {
      updateByBatchInner(rows, options);
    } catch (Exception e) {
      try {
        updateByBatchInner(rows, options);
      } catch (Exception e1) {
        throw new RuntimeException(e);
      }
    } finally {
      resetDbLock.readLock().unlock();
    }
  }

  @Override
  public boolean flush() {
    return false;
  }

  @Override
  public void closeDB() {
    resetDbLock.writeLock().lock();
    try {
      if (!isAlive()) {
        return;
      }
      database.close();
      alive = false;
    } catch (IOException e) {
      logger.error("Failed to find the dbStore file on the closeDB: {} ", dataBaseName);
    } finally {
      resetDbLock.writeLock().unlock();
    }
  }

  @Override
  public com.wizbl.core.db.common.iterator.DBIterator iterator() {
    return new StoreIterator(database.iterator());
  }

  public Stream<Entry<byte[], byte[]>> stream() {
    return StreamSupport.stream(spliterator(), false);
  }

  public Stream<Entry<byte[], byte[]>> parallelStream() {
    return StreamSupport.stream(spliterator(), true);
  }

}
