package com.litongjava.voice.agent.sip;

import com.litongjava.sip.model.CallSession;
import com.litongjava.sip.rtp.codec.AudioResampler;
import com.litongjava.sip.rtp.codec.PcmCodec;
import com.litongjava.sip.rtp.media.AudioFrame;
import com.litongjava.sip.rtp.media.MediaProcessor;
import com.litongjava.tio.utils.environment.EnvUtils;
import com.litongjava.tio.utils.hutool.StrUtil;
import com.litongjava.voice.agent.bridge.RealtimeModelBridge;
import com.litongjava.voice.agent.bridge.RealtimeModelBridgeFactory;
import com.litongjava.voice.agent.callback.RealtimeSetupCallback;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RealtimeMediaProcessor implements MediaProcessor {

  private final String platform;
  private final RealtimeSetupCallback realtimeSetupCallback;
  private final SipSessionRegistry sessionRegistry;

  public RealtimeMediaProcessor(RealtimeSetupCallback realtimeSetupCallback) {
    this(EnvUtils.getStr("vioce.agent.platform"), realtimeSetupCallback, new SipSessionRegistry());
  }

  public RealtimeMediaProcessor(String platform, RealtimeSetupCallback realtimeSetupCallback,
      SipSessionRegistry sessionRegistry) {
    this.platform = platform;
    this.realtimeSetupCallback = realtimeSetupCallback;
    this.sessionRegistry = sessionRegistry;
  }

  @Override
  public AudioFrame process(AudioFrame input, CallSession session) {
    if (input == null || session == null) {
      return null;
    }

    String callId = getCallId(session);
    if (StrUtil.isBlank(callId)) {
      log.warn("callId is blank, skip processing");
      return null;
    }

    SipRealtimeSession sipSession = sessionRegistry.getOrCreate(callId, this::createSipSession);

    try {
      sipSession.ensureConnected(session);
      short[] in8k = input.getSamples();
      if (in8k == null || in8k.length == 0) {
        return null;
      }

      short[] pcm16k = AudioResampler.resample(in8k, 8000, 16000);
      byte[] pcm16kBytes = PcmCodec.shortsToLittleEndianBytes(pcm16k);

      sipSession.sendToModel(pcm16kBytes);

      short[] out8k = sipSession.takeOutputFrame(in8k.length);
      if (out8k == null) {
        return null;
      }

      return new AudioFrame(out8k, 8000, 1, input.getRtpTimestamp());
    } catch (Exception e) {
      log.error("process failed, callId={}", callId, e);
      return null;
    }
  }

  public void close(CallSession session) {
    if (session == null) {
      return;
    }
    closeByCallId(getCallId(session));
  }

  public void closeByCallId(String callId) {
    if (StrUtil.isBlank(callId)) {
      return;
    }
    sessionRegistry.remove(callId);
  }

  public void closeAll() {
    sessionRegistry.clear();
  }

  private SipRealtimeSession createSipSession(String callId) {
    SipRealtimeBridgeCallback callback = new SipRealtimeBridgeCallback(callId);
    RealtimeModelBridge bridge = RealtimeModelBridgeFactory.createBridge(platform, callback);
    SipRealtimeSession sipSession = new SipRealtimeSession(callId, bridge, callback, realtimeSetupCallback);
    callback.bind(sipSession);
    log.info("created realtime sip session, callId={}", callId);
    return sipSession;
  }

  private String getCallId(CallSession session) {
    try {
      String callId = session.getCallId();
      return callId == null ? null : callId.trim();
    } catch (Exception e) {
      log.warn("failed to get callId from CallSession", e);
      return null;
    }
  }
}