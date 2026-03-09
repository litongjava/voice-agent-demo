package com.litongjava.voice.agent.sip;

import com.litongjava.sip.model.CallSession;
import com.litongjava.voice.agent.bridge.RealtimeSetup;
import com.litongjava.voice.agent.callback.RealtimeSetupCallback;

public class SipRealtimeSetupCallback implements RealtimeSetupCallback {

  @Override
  public RealtimeSetup getRealtimeSetup(CallSession session) {
    return RealtimeSetupFactory.buildFromEnv();
  }
}
