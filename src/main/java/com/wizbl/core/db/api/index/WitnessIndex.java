package com.wizbl.core.db.api.index;

import com.googlecode.cqengine.attribute.Attribute;
import com.googlecode.cqengine.attribute.SimpleAttribute;
import com.googlecode.cqengine.index.disk.DiskIndex;
import com.googlecode.cqengine.persistence.disk.DiskPersistence;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import com.wizbl.common.utils.ByteArray;
import com.wizbl.core.capsule.WitnessCapsule;
import com.wizbl.core.db.common.WrappedByteArray;
import com.wizbl.core.db2.core.IBrte2ChainBase;
import com.wizbl.protos.Protocol.Witness;

import javax.annotation.PostConstruct;

import static com.googlecode.cqengine.query.QueryFactory.attribute;

@Component
@Slf4j
public class WitnessIndex extends AbstractIndex<WitnessCapsule, Witness> {

  public static SimpleAttribute<WrappedByteArray, String> Witness_ADDRESS;
  public static Attribute<WrappedByteArray, String> PUBLIC_KEY;
  public static Attribute<WrappedByteArray, String> Witness_URL;

  @Autowired
  public WitnessIndex(
      @Qualifier("witnessStore") final IBrte2ChainBase<WitnessCapsule> database) {
    super(database);
  }

  @PostConstruct
  public void init() {
    initIndex(DiskPersistence.onPrimaryKeyInFile(Witness_ADDRESS, indexPath));
//    index.addIndex(DiskIndex.onAttribute(Witness_ADDRESS));
    index.addIndex(DiskIndex.onAttribute(PUBLIC_KEY));
    index.addIndex(DiskIndex.onAttribute(Witness_URL));
  }

  @Override
  public void setAttribute() {
    Witness_ADDRESS =
        attribute("witness address",
            bytes -> ByteArray.toHexString(getObject(bytes).getAddress().toByteArray()));
    PUBLIC_KEY =
        attribute("public key",
            bytes -> ByteArray.toHexString(getObject(bytes).getPubKey().toByteArray()));
    Witness_URL =
        attribute("witness url", bytes -> getObject(bytes).getUrl());

  }

}
