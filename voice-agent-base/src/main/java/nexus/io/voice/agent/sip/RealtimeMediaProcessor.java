package nexus.io.voice.agent.sip;

import com.litongjava.sip.model.CallSession;
import com.litongjava.sip.rtp.codec.AudioResampler;
import com.litongjava.sip.rtp.codec.NegotiatedAudioFormatResolver;
import com.litongjava.sip.rtp.codec.PcmCodec;
import com.litongjava.sip.rtp.media.AudioFrame;
import com.litongjava.sip.rtp.media.MediaProcessor;

import lombok.extern.slf4j.Slf4j;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.tio.utils.hutool.StrUtil;
import nexus.io.voice.agent.bridge.RealtimeModelBridge;
import nexus.io.voice.agent.bridge.RealtimeModelBridgeFactory;
import nexus.io.voice.agent.callback.RealtimeSetupCallback;

@Slf4j
public class RealtimeMediaProcessor implements MediaProcessor {

  private static final int MODEL_INPUT_SAMPLE_RATE = 16000;
  private static final int MODEL_OUTPUT_SAMPLE_RATE = 24000;

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

      short[] inputSamples = input.getSamples();
      if (inputSamples == null || inputSamples.length == 0) {
        return null;
      }

      int sessionSampleRate = NegotiatedAudioFormatResolver.resolveSessionPcmSampleRate(session);
      int inputSampleRate = input.getSampleRate() > 0 ? input.getSampleRate() : sessionSampleRate;

      short[] modelInputSamples = inputSamples;
      if (inputSampleRate != MODEL_INPUT_SAMPLE_RATE) {
        AudioResampler resampler = session.getOrCreateInputResampler(inputSampleRate, MODEL_INPUT_SAMPLE_RATE);
        modelInputSamples = resampler.resample(inputSamples);
      }

      byte[] pcm16kBytes = PcmCodec.shortsToLittleEndianBytes(modelInputSamples);
      sipSession.sendToModel(pcm16kBytes);

      short[] outputSamples = sipSession.takeOutputFrame(inputSamples.length);
      if (outputSamples == null) {
        return null;
      }

      int channels = NegotiatedAudioFormatResolver.resolveChannels(session);
      long rtpTimestamp = input.getRtpTimestamp();
      return new AudioFrame(outputSamples, sessionSampleRate, channels, rtpTimestamp);

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

  public static int getModelInputSampleRate() {
    return MODEL_INPUT_SAMPLE_RATE;
  }

  public static int getModelOutputSampleRate() {
    return MODEL_OUTPUT_SAMPLE_RATE;
  }
}