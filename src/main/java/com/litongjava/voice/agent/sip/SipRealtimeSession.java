package com.litongjava.voice.agent.sip;

import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import com.litongjava.sip.model.CallSession;
import com.litongjava.sip.rtp.codec.AudioResampler;
import com.litongjava.sip.rtp.codec.PcmCodec;
import com.litongjava.voice.agent.bridge.RealtimeModelBridge;
import com.litongjava.voice.agent.bridge.RealtimeSetup;
import com.litongjava.voice.agent.callback.RealtimeSetupCallback;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SipRealtimeSession {

  private static final int MODEL_OUTPUT_SAMPLE_RATE = 24000;
  private static final int SIP_SAMPLE_RATE = 8000;

  private final String callId;
  private final RealtimeModelBridge bridge;
  private final SipRealtimeBridgeCallback callback;
  private final RealtimeSetupCallback realtimeSetupCallback;
  private final AtomicBoolean connected = new AtomicBoolean(false);
  private final ConcurrentLinkedQueue<Short> outputQueue = new ConcurrentLinkedQueue<>();

  public SipRealtimeSession(String callId, RealtimeModelBridge bridge, SipRealtimeBridgeCallback callback,
      RealtimeSetupCallback realtimeSetupCallback) {
    this.callId = callId;
    this.bridge = bridge;
    this.callback = callback;
    this.realtimeSetupCallback = realtimeSetupCallback;
  }

  public void ensureConnected(CallSession session) {
    if (connected.compareAndSet(false, true)) {
      RealtimeSetup setup = null;
      if (realtimeSetupCallback != null) {
        setup = realtimeSetupCallback.getRealtimeSetup(null);
      }
      callback.start(setup);
      bridge.connect(setup).exceptionally(ex -> {
        log.error("bridge connect failed, callId={}", callId, ex);
        connected.set(false);
        return null;
      });
    }
  }

  public void sendToModel(byte[] pcm16kBytes) {
    if (pcm16kBytes == null || pcm16kBytes.length == 0) {
      return;
    }

    bridge.sendPcm16k(pcm16kBytes).exceptionally(ex -> {
      log.warn("sendPcm16k failed, callId={}", callId, ex);
      return null;
    });
  }

  public void appendModelAudio(byte[] pcmBytes) {
    if (pcmBytes == null || pcmBytes.length == 0) {
      return;
    }

    short[] pcm24k = PcmCodec.littleEndianBytesToShorts(pcmBytes);
    if (pcm24k.length == 0) {
      return;
    }

    short[] pcm8k = AudioResampler.resample(pcm24k, MODEL_OUTPUT_SAMPLE_RATE, SIP_SAMPLE_RATE);
    for (short sample : pcm8k) {
      outputQueue.offer(sample);
    }
  }

  public short[] takeOutputFrame(int frameSamples) {
    if (frameSamples <= 0) {
      return null;
    }

    if (outputQueue.peek() == null) {
      return null;
    }

    short[] out = new short[frameSamples];
    int i = 0;
    for (; i < frameSamples; i++) {
      Short value = outputQueue.poll();
      if (value == null) {
        break;
      }
      out[i] = value;
    }

    if (i < frameSamples) {
      Arrays.fill(out, i, frameSamples, (short) 0);
    }

    return out;
  }

  public void close() {
    try {
      bridge.close().getNow(null);
    } catch (Exception e) {
      log.warn("bridge.close failed, callId={}", callId, e);
    } finally {
      outputQueue.clear();
      connected.set(false);
    }
  }

  public String getCallId() {
    return callId;
  }
}