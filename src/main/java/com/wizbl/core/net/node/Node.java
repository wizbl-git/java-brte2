package com.wizbl.core.net.node;

import com.wizbl.common.overlay.message.Message;
import com.wizbl.common.utils.Quitable;
import com.wizbl.common.utils.Sha256Hash;

public interface Node extends Quitable {

  void setNodeDelegate(NodeDelegate nodeDel);

  void broadcast(Message msg);

  void listen();

  void syncFrom(Sha256Hash myHeadBlockHash);

  void close() throws InterruptedException;
}
