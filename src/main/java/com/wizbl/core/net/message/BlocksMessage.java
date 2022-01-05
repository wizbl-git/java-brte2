package com.wizbl.core.net.message;

import java.util.List;
import org.apache.commons.collections4.CollectionUtils;
import com.wizbl.protos.Protocol.Block;
import com.wizbl.protos.Protocol.Items;

public class BlocksMessage extends Brte2Message {

  private List<Block> blocks;

  public BlocksMessage(byte[] data) throws Exception {
    this.type = MessageTypes.BLOCKS.asByte();
    this.data = data;
    Items items = Items.parseFrom(data);
    if (items.getType() == Items.ItemType.BLOCK) {
      blocks = items.getBlocksList();
    }
  }

  public List<Block> getBlocks() {
    return blocks;
  }

  @Override
  public String toString() {
    return super.toString() + "size: " + (CollectionUtils.isNotEmpty(blocks) ? blocks
        .size() : 0);
  }

  @Override
  public Class<?> getAnswerMessage() {
    return null;
  }

}
