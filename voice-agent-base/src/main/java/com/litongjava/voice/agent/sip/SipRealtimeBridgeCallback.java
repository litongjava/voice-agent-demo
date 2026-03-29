package com.litongjava.voice.agent.sip;

import java.util.Base64;

import com.litongjava.tio.utils.hutool.StrUtil;
import com.litongjava.voice.agent.bridge.RealtimeBridgeCallback;
import com.litongjava.voice.agent.bridge.RealtimeSetup;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SipRealtimeBridgeCallback implements RealtimeBridgeCallback {

  private final String callId;
  private volatile SipRealtimeSession sipSession;

  public SipRealtimeBridgeCallback(String callId) {
    this.callId = callId;
  }

  public void bind(SipRealtimeSession sipSession) {
    this.sipSession = sipSession;
  }

  @Override
  public void start(RealtimeSetup setup) {
    log.info("realtime callback start, callId={}", callId);
  }

  @Override
  public void sendText(String text) {
    if (StrUtil.isNotBlank(text)) {
      log.debug("realtime text event, callId={}, text={}", callId, text);
    }
  }

  @Override
  public void sendBinary(byte[] bytes) {
    SipRealtimeSession session = this.sipSession;
    if (session == null || bytes == null || bytes.length == 0) {
      return;
    }
    session.appendModelAudio(bytes);
  }

  @Override
  public void close(String reason) {
    log.info("realtime callback close, callId={}, reason={}", callId, reason);
  }

  @Override
  public void session(String sessionId) {
    // TODO Auto-generated method stub

  }

  @Override
  public void turnComplete(String role, String text) {
    // TODO Auto-generated method stub

  }

  @Override
  public void sendAudio(String turnId, String audioBase64) {
    byte[] bytes = Base64.getDecoder().decode(audioBase64);
    SipRealtimeSession session = this.sipSession;
    if (session == null || bytes == null || bytes.length == 0) {
      return;
    }
    session.appendModelAudio(bytes);

  }

  @Override
  public void onUserAudioActivity() {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void onUserTextActivity(String text) {
    // TODO Auto-generated method stub
    
  }
}