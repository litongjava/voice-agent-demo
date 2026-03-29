package com.litongjava.voice.agent.sip;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class SipSessionRegistry {

  private final Map<String, SipRealtimeSession> sessions = new ConcurrentHashMap<>();

  public SipRealtimeSession getOrCreate(String callId, Function<String, SipRealtimeSession> creator) {
    return sessions.computeIfAbsent(callId, creator);
  }

  public SipRealtimeSession get(String callId) {
    return sessions.get(callId);
  }

  public void remove(String callId) {
    SipRealtimeSession session = sessions.remove(callId);
    if (session != null) {
      session.close();
    }
  }

  public void clear() {
    for (SipRealtimeSession session : sessions.values()) {
      if (session != null) {
        session.close();
      }
    }
    sessions.clear();
  }
}