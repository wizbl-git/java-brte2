package com.wizbl.core.net.message;

import java.util.List;
import com.wizbl.common.utils.Sha256Hash;
import com.wizbl.protos.Protocol.Inventory;
import com.wizbl.protos.Protocol.Inventory.InventoryType;

public class TransactionInventoryMessage extends InventoryMessage {

  public TransactionInventoryMessage(byte[] packed) throws Exception {
    super(packed);
  }

  public TransactionInventoryMessage(Inventory inv) {
    super(inv);
  }

  public TransactionInventoryMessage(List<Sha256Hash> hashList) {
    super(hashList, InventoryType.TRX);
  }
}
