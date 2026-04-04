package nexus.io.voice.agent.callback;

import com.litongjava.sip.model.CallSession;

import nexus.io.voice.agent.bridge.RealtimeSetup;

public interface RealtimeSetupCallback {

  RealtimeSetup getRealtimeSetup(CallSession session);
}