package com.wizbl.core.db.api.index;

import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.persistence.disk.DiskPersistence;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import com.wizbl.common.utils.ByteArray;
import com.wizbl.core.capsule.AccountCapsule;
import com.wizbl.core.db.common.WrappedByteArray;
import com.wizbl.core.db2.core.IBrte2ChainBase;
import com.wizbl.protos.Protocol.Account;

import javax.annotation.PostConstruct;

import static com.googlecode.cqengine.query.QueryFactory.attribute;

@Component
@Slf4j
public class AccountIndex extends AbstractIndex<AccountCapsule, Account> {

  public static SimpleAttribute<WrappedByteArray, String> Account_ADDRESS;

  @Autowired
  public AccountIndex(@Qualifier("accountStore") final IBrte2ChainBase<AccountCapsule> database) {
    super(database);
  }

  @PostConstruct
  public void init() {
    initIndex(DiskPersistence.onPrimaryKeyInFile(Account_ADDRESS, indexPath));
//    index.addIndex(DiskIndex.onAttribute(Account_ADDRESS));
  }

  @Override
  protected void setAttribute() {
    Account_ADDRESS = attribute("account address",
        bytes -> ByteArray.toHexString(bytes.getBytes()));
  }
}
