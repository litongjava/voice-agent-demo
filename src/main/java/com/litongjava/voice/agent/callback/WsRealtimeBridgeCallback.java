package com.litongjava.voice.agent.callback;

import java.nio.file.Path;

import com.litongjava.media.NativeMedia;
import com.litongjava.tio.core.ChannelContext;
import com.litongjava.tio.core.Tio;
import com.litongjava.tio.websocket.common.WebSocketResponse;
import com.litongjava.voice.agent.audio.AudioFinishCallback;
import com.litongjava.voice.agent.audio.SessionAudioRecorder;
import com.litongjava.voice.agent.bridge.RealtimeBridgeCallback;
import com.litongjava.voice.agent.bridge.RealtimeSetup;
import com.litongjava.voice.agent.consts.VoiceAgentConst;
import com.litongjava.voice.agent.utils.ChannelContextUtils;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class WsRealtimeBridgeCallback implements RealtimeBridgeCallback {

  private ChannelContext channelContext;
  private String sessionId;

  public WsRealtimeBridgeCallback(ChannelContext channelContext) {
    this.channelContext = channelContext;
    this.sessionId = ChannelContextUtils.key(channelContext);
  }

  @Override
  public void sendText(String json) {
    WebSocketResponse wsResp = WebSocketResponse.fromText(json, VoiceAgentConst.CHARSET);
    Tio.send(channelContext, wsResp);
  }

  @Override
  public void sendBinary(byte[] bytes) {
    try {
      SessionAudioRecorder.appendModelPcm(sessionId, bytes);
    } catch (Exception ex) {
      log.warn("record model pcm failed: {}", ex.getMessage());
    }
    WebSocketResponse wsResp = WebSocketResponse.fromBytes(bytes);
    Tio.send(channelContext, wsResp);
  }

  @Override
  public void close(String reason) {
    AudioFinishCallback audioFinishCallback = new AudioFinishCallback() {

      @Override
      public void done(Path audioFile) {
        String wavFilePath = audioFile.toString();
        String mp3 = NativeMedia.toMp3(wavFilePath);
      }
    };
    SessionAudioRecorder.stop(sessionId, audioFinishCallback);
    Tio.remove(channelContext, reason);
  }

  @Override
  public void session(String sessionId) {
  }

  @Override
  public void turnComplete(String role, String text) {
   
    // log.info("role:{},text:{}", role, text);
  }

  @Override
  public void start(RealtimeSetup setup) {
    
  }
}
