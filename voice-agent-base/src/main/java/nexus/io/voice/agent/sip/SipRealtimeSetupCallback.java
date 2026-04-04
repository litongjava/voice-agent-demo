package nexus.io.voice.agent.sip;

import com.litongjava.sip.model.CallSession;

import nexus.io.voice.agent.bridge.RealtimeSetup;
import nexus.io.voice.agent.callback.RealtimeSetupCallback;

public class SipRealtimeSetupCallback implements RealtimeSetupCallback {

  @Override
  public RealtimeSetup getRealtimeSetup(CallSession session) {
    return RealtimeSetupFactory.buildFromEnv();
  }
}
