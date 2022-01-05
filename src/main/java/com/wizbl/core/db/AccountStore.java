package com.wizbl.core.db;

import com.wizbl.core.Wallet;
import com.wizbl.core.capsule.AccountCapsule;
import com.typesafe.config.ConfigObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class AccountStore extends Brte2StoreWithRevoking<AccountCapsule> {

  private static final Map<String, byte[]> assertsAddress = new HashMap<>(); // key = name , value = address

  @Autowired
  private AccountStore(@Value("account") String dbName) {
    super(dbName);
  }

  @Override
  public AccountCapsule get(byte[] key) {
    byte[] value = revokingDB.getUnchecked(key);
    return ArrayUtils.isEmpty(value) ? null : new AccountCapsule(value);
  }

  /**
   * Max ACN account.
   */
  public AccountCapsule getSun() {
    return getUnchecked(assertsAddress.get("Sun"));
  }

  /**
   * Min ACN account.
   */
  public AccountCapsule getSquirrel() {
    return getUnchecked(assertsAddress.get("Squirrel"));
  }

  /**
   * Get foundation account info.
   */
  public AccountCapsule getOak() {
    return getUnchecked(assertsAddress.get("Oak"));
  }

  /**
   * Coin Fee account.
   */
  public AccountCapsule getCoinFeeAccount() {
    return getUnchecked(assertsAddress.get("fee.account"));
  }

  /**
   * Energy Fee account.
   */
  public AccountCapsule getEnergyFeeAccount() {
    return getUnchecked(assertsAddress.get("energy.account"));
  }

  /**
   * bandwidth Fee account.
   */
  public AccountCapsule getBandWidthFeeAccount() {
    return getUnchecked(assertsAddress.get("bandwidth.account"));
  }

  public static void setAccount(com.typesafe.config.Config config) {
    List list = config.getObjectList("genesis.block.assets");
    for (int i = 0; i < list.size(); i++) {
      ConfigObject obj = (ConfigObject) list.get(i);
      String accountName = obj.get("accountName").unwrapped().toString();
      byte[] address = Wallet.decodeFromBase58Check(obj.get("address").unwrapped().toString());
      assertsAddress.put(accountName, address);
    }
  }
}
