package com.litongjava.voice.agent.callback;

import com.litongjava.sip.model.CallSession;
import com.litongjava.voice.agent.bridge.RealtimeSetup;

public interface RealtimeSetupCallback {

  RealtimeSetup getRealtimeSetup(CallSession session);
}