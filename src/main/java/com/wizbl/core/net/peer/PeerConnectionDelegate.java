package com.wizbl.core.net.peer;

import com.wizbl.common.overlay.message.Message;
import com.wizbl.common.utils.Sha256Hash;
import com.wizbl.core.net.message.Brte2Message;

public abstract class PeerConnectionDelegate {

  public abstract void onMessage(PeerConnection peer, Brte2Message msg) throws Exception;

  public abstract Message getMessage(Sha256Hash msgId);

  public abstract void onConnectPeer(PeerConnection peer);

  public abstract void onDisconnectPeer(PeerConnection peer);

}
