package com.litongjava.voice.agent.handler;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.litongjava.media.NativeMedia;
import com.litongjava.tio.consts.TioConst;
import com.litongjava.tio.core.ChannelContext;
import com.litongjava.tio.core.Tio;
import com.litongjava.tio.http.common.HttpRequest;
import com.litongjava.tio.http.common.HttpResponse;
import com.litongjava.tio.utils.json.JsonUtils;
import com.litongjava.tio.websocket.common.WebSocketRequest;
import com.litongjava.tio.websocket.common.WebSocketResponse;
import com.litongjava.tio.websocket.common.WebSocketSessionContext;
import com.litongjava.tio.websocket.server.handler.IWebSocketHandler;
import com.litongjava.voice.agent.audio.AudioFinishCallback;
import com.litongjava.voice.agent.audio.SessionAudioRecorder;
import com.litongjava.voice.agent.bridge.RealtimeModelBridge;
import com.litongjava.voice.agent.bridge.RealtimeModelBridgeFactory;
import com.litongjava.voice.agent.bridge.RealtimeSetup;
import com.litongjava.voice.agent.callback.WsRealtimeBridgeCallback;
import com.litongjava.voice.agent.model.WsVoiceAgentRequestMessage;
import com.litongjava.voice.agent.model.WsVoiceAgentResponseMessage;
import com.litongjava.voice.agent.model.WsVoiceAgentType;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class VoiceSocketHandler implements IWebSocketHandler {

  /**
   * 一个前端连接一个 bridge
   */
  private static final Map<String, RealtimeModelBridge> BRIDGES = new ConcurrentHashMap<>();

  /**
   * 一个前端连接一个 callback
   */
  private static final Map<String, WsRealtimeBridgeCallback> CALLBACKS = new ConcurrentHashMap<>();

  /**
   * 主动介入总开关
   */
  private static final boolean ENABLE_PROACTIVE_INTERVENTION = true;

  /**
   * assistant 完成回复后，用户沉默多久开始主动介入
   */
  private static final long PROACTIVE_INTERVENTION_TIMEOUT_MS = 8_000L;

  /**
   * 两次主动介入之间的最小间隔
   */
  private static final long PROACTIVE_INTERVENTION_REPEAT_MS = 8_000L;

  @Override
  public HttpResponse handshake(HttpRequest httpRequest, HttpResponse response, ChannelContext channelContext)
      throws Exception {
    log.info("请求信息: {}", httpRequest);
    return response;
  }

  @Override
  public void onAfterHandshaked(HttpRequest httpRequest, HttpResponse httpResponse, ChannelContext channelContext)
      throws Exception {
    log.info("握手完成: {}", httpRequest);
  }

  @Override
  public Object onClose(WebSocketRequest wsRequest, byte[] bytes, ChannelContext channelContext) throws Exception {
    String sessionKey = channelContext.getId();
    cleanupSession(channelContext, sessionKey, "客户端主动关闭连接");
    return null;
  }

  @Override
  public Object onBytes(WebSocketRequest wsRequest, byte[] bytes, ChannelContext channelContext) throws Exception {
    String sessionKey = channelContext.getId();

    // 这里只表示“麦克风流有数据”，不代表用户真的开口，所以只做轻量触达
    WsRealtimeBridgeCallback callback = CALLBACKS.get(sessionKey);
    if (callback != null) {
      callback.onUserAudioActivity();
    }

    try {
      SessionAudioRecorder.appendUserPcm(sessionKey, bytes);
    } catch (Exception ex) {
      log.warn("appendUserPcm failed: {}", ex.getMessage());
    }

    RealtimeModelBridge bridge = BRIDGES.get(sessionKey);
    if (bridge != null) {
      try {
        bridge.sendPcm16k(bytes);
      } catch (Exception e) {
        log.error("bridge.sendPcm16k error, sessionKey:{}", sessionKey, e);
      }
    } else {
      log.warn("bridge not found when onBytes, sessionKey:{}", sessionKey);
    }

    return null;
  }

  @Override
  public Object onText(WebSocketRequest wsRequest, String text, ChannelContext channelContext) throws Exception {
    WebSocketSessionContext wsSessionContext = (WebSocketSessionContext) channelContext.get();
    String path = wsSessionContext.getHandshakeRequest().getRequestLine().path;
    log.info("路径：{}，收到消息：{}", path, text);

    String rawText = text == null ? "" : text.trim();

    WsVoiceAgentRequestMessage msg = null;
    try {
      msg = JsonUtils.parse(rawText, WsVoiceAgentRequestMessage.class);
    } catch (Exception je) {
      log.debug("收到非 JSON 文本或无法解析为 WsMessage: {}", je.getMessage());
      return null;
    } catch (Throwable e) {
      log.error("解析收到的消息异常", e);
      return null;
    }

    String sessionKey = channelContext.getId();
    RealtimeModelBridge bridge = BRIDGES.get(sessionKey);

    if (bridge == null && msg != null && msg.getType() != null) {
      WsVoiceAgentType typeEnum = parseType(msg.getType());

      if (typeEnum == WsVoiceAgentType.SETUP) {
        String platform = msg.getPlatform();
        String systemPrompt = msg.getSystem_prompt();
        String userPrompt = msg.getUser_prompt();
        String greeting = msg.getGreeting();

        RealtimeSetup realtimeSetup = new RealtimeSetup(systemPrompt, userPrompt, greeting);

        connectLLM(channelContext, platform, realtimeSetup);

        WsVoiceAgentResponseMessage resp = new WsVoiceAgentResponseMessage(WsVoiceAgentType.SETUP_RECEIVED.name());
        resp.setSessionId(sessionKey);

        String json = toJson(resp);
        Tio.send(channelContext, WebSocketResponse.fromText(json, TioConst.UTF_8));
      } else {
        log.warn("bridge not ready and first message is not SETUP, sessionKey:{}, type:{}", sessionKey, msg.getType());
      }

      return null;
    }

    if (bridge == null) {
      String respJson = toJson(new WsVoiceAgentResponseMessage(WsVoiceAgentType.ERROR.name(), "no bridge"));
      Tio.send(channelContext, WebSocketResponse.fromText(respJson, TioConst.UTF_8));
      return null;
    }

    try {
      if (msg != null && msg.getType() != null) {
        WsVoiceAgentType typeEnum = parseType(msg.getType());

        if (typeEnum != null) {
          switch (typeEnum) {
          case AUDIO_END: {
            bridge.endAudioInput();
            break;
          }

          case TEXT: {
            String userText = msg.getText() == null ? "" : msg.getText();

            WsRealtimeBridgeCallback callback = CALLBACKS.get(sessionKey);
            if (callback != null) {
              callback.onUserTextActivity(userText);
            }

            bridge.sendText(userText);
            break;
          }

          case CLOSE: {
            cleanupSession(channelContext, sessionKey, "client requested close");
            break;
          }

          default: {
            Tio.send(channelContext, WebSocketResponse.fromText(
                toJson(new WsVoiceAgentResponseMessage(WsVoiceAgentType.IGNORED.name(), rawText)), TioConst.UTF_8));
            break;
          }
          }
        } else {
          log.debug("未知的 type: {}", msg.getType());
        }
      }
    } catch (Exception e) {
      log.error("onText handle error, sessionKey:{}", sessionKey, e);
    }

    return null;
  }

  private void connectLLM(ChannelContext channelContext, String platform, RealtimeSetup setup) {
    String sessionKey = channelContext.getId();

    AudioFinishCallback audioFinishCallback = new AudioFinishCallback() {
      @Override
      public void done(Path audioFile) {
        String wavFilePath = audioFile.toString();
        NativeMedia.toMp3(wavFilePath);
      }
    };

    WsRealtimeBridgeCallback callback = new WsRealtimeBridgeCallback(channelContext, audioFinishCallback);
    callback.configureProactiveIntervention(ENABLE_PROACTIVE_INTERVENTION, PROACTIVE_INTERVENTION_TIMEOUT_MS,
        PROACTIVE_INTERVENTION_REPEAT_MS);

    try {
      SessionAudioRecorder.start(sessionKey, 16000, 24000);
    } catch (Exception e) {
      log.warn("start recorder failed: {}", e.getMessage());
    }

    RealtimeModelBridge bridge = RealtimeModelBridgeFactory.createBridge(platform, callback);

    callback.bindModelTextSender(prompt -> {
      try {
        RealtimeModelBridge b = BRIDGES.get(sessionKey);
        if (b != null) {
          b.sendText(prompt);
        } else {
          log.warn("bridge not found when proactive intervention, sessionKey:{}", sessionKey);
        }
      } catch (Exception e) {
        log.warn("bridge.sendText failed, sessionKey:{}, prompt:{}", sessionKey, prompt, e);
      }
    });

    callback.start(setup);

    CALLBACKS.put(sessionKey, callback);
    BRIDGES.put(sessionKey, bridge);

    try {
      bridge.connect(setup);
    } catch (Exception e) {
      log.error("bridge.connect error, sessionKey:{}", sessionKey, e);
      cleanupSession(channelContext, sessionKey, "bridge connect failed");
    }
  }

  private void cleanupSession(ChannelContext channelContext, String sessionKey, String reason) {
    WsRealtimeBridgeCallback callback = CALLBACKS.remove(sessionKey);
    RealtimeModelBridge bridge = BRIDGES.remove(sessionKey);

    if (bridge != null) {
      try {
        bridge.close();
      } catch (Exception e) {
        log.warn("bridge.close error, sessionKey:{}", sessionKey, e);
      }
      return;
    }

    if (callback != null) {
      try {
        callback.close(reason);
      } catch (Exception e) {
        log.warn("callback.close error, sessionKey:{}", sessionKey, e);
      }
      return;
    }

    try {
      Tio.remove(channelContext, reason);
    } catch (Exception e) {
      log.warn("Tio.remove error, sessionKey:{}", sessionKey, e);
    }
  }

  private WsVoiceAgentType parseType(String type) {
    if (type == null) {
      return null;
    }

    try {
      return WsVoiceAgentType.valueOf(type.trim().toUpperCase());
    } catch (Exception e) {
      return null;
    }
  }

  private String toJson(WsVoiceAgentResponseMessage wsVoiceAgentResponseMessage) {
    return JsonUtils.toSkipNullJson(wsVoiceAgentResponseMessage);
  }
}