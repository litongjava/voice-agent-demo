package com.litongjava.voice.agent.handler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.litongjava.tio.core.ChannelContext;
import com.litongjava.tio.core.Tio;
import com.litongjava.tio.http.common.HttpRequest;
import com.litongjava.tio.http.common.HttpResponse;
import com.litongjava.tio.utils.json.JsonUtils;
import com.litongjava.tio.websocket.common.WebSocketRequest;
import com.litongjava.tio.websocket.common.WebSocketResponse;
import com.litongjava.tio.websocket.common.WebSocketSessionContext;
import com.litongjava.tio.websocket.server.handler.IWebSocketHandler;
import com.litongjava.voice.agent.audio.SessionAudioRecorder;
import com.litongjava.voice.agent.bridge.RealtimeBridgeCallback;
import com.litongjava.voice.agent.bridge.RealtimeModelBridge;
import com.litongjava.voice.agent.bridge.RealtimeModelBridgeFactory;
import com.litongjava.voice.agent.bridge.RealtimeSetup;
import com.litongjava.voice.agent.callback.WsRealtimeBridgeCallback;
import com.litongjava.voice.agent.consts.VoiceAgentConst;
import com.litongjava.voice.agent.model.WsVoiceAgentRequestMessage;
import com.litongjava.voice.agent.model.WsVoiceAgentResponseMessage;
import com.litongjava.voice.agent.model.WsVoiceAgentType;
import com.litongjava.voice.agent.utils.ChannelContextUtils;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class VoiceSocketHandler implements IWebSocketHandler {
  // 一个前端连接一个 bridge
  private static final Map<String, RealtimeModelBridge> BRIDGES = new ConcurrentHashMap<>();

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
    String k = ChannelContextUtils.key(channelContext);
    RealtimeModelBridge bridge = BRIDGES.remove(k);
    if (bridge != null) {
      bridge.close();
    }
    Tio.remove(channelContext, "客户端主动关闭连接");
    return null;
  }

  @Override
  public Object onBytes(WebSocketRequest wsRequest, byte[] bytes, ChannelContext channelContext) throws Exception {
    String k = ChannelContextUtils.key(channelContext);
    // 前端推：16k PCM mono 裸流,记录用户上行音频（前端发来 16k PCM）
    try {
      SessionAudioRecorder.appendUserPcm(k, bytes);
    } catch (Exception ex) {
      log.warn("appendUserPcm failed: {}", ex.getMessage());
    }

    RealtimeModelBridge bridge = BRIDGES.get(ChannelContextUtils.key(channelContext));
    if (bridge != null) {
      bridge.sendPcm16k(bytes);
    }
    return null;
  }

  @Override
  public Object onText(WebSocketRequest wsRequest, String text, ChannelContext channelContext) throws Exception {
    WebSocketSessionContext wsSessionContext = (WebSocketSessionContext) channelContext.get();
    String path = wsSessionContext.getHandshakeRequest().getRequestLine().path;
    log.info("路径：{}，收到消息：{}", path, text);

    String t = text == null ? "" : text.trim();

    // 先尝试解析为 JSON -> WsMessage
    WsVoiceAgentRequestMessage msg = null;
    try {
      msg = JsonUtils.parse(t, WsVoiceAgentRequestMessage.class);
    } catch (Exception je) {
      // 解析失败：降级为普通文本处理
      log.debug("收到非 JSON 文本或无法解析为 WsMessage", je.getMessage());
      return null;
    } catch (Throwable e) {
      log.error("解析收到的消息异常", e);
      return null;
    }
    RealtimeModelBridge bridge = BRIDGES.get(ChannelContextUtils.key(channelContext));

    if (bridge == null && msg != null && msg.getType() != null) {
      String typeStr = msg.getType().trim().toUpperCase();
      WsVoiceAgentType typeEnum = null;
      try {
        typeEnum = WsVoiceAgentType.valueOf(typeStr);
      } catch (Exception ex) {
        // 未识别的 type，降级处理
        log.debug("未知的 type: {}", typeStr);
      }
      switch (typeEnum) {
      case SETUP:
        String platform = msg.getPlatform();
        String systemPrompt = msg.getSystem_prompt();
        String user_prompt = msg.getUser_prompt();
        String job_description = msg.getJob_description();
        String resume = msg.getResume();
        String questions = msg.getQuestions();
        String greeting = msg.getGreeting();

        RealtimeSetup realtimeSetup = new RealtimeSetup(systemPrompt, user_prompt, job_description, resume, questions,
            greeting);

        connectLLM(channelContext, platform, realtimeSetup);
        // 回显确认
        String id = ChannelContextUtils.key(channelContext);
        WsVoiceAgentResponseMessage wsVoiceAgentResponseMessage = new WsVoiceAgentResponseMessage(WsVoiceAgentType.SETUP_RECEIVED.name());
        wsVoiceAgentResponseMessage.setSessionId(id);
        String json = toJson(wsVoiceAgentResponseMessage);
        Tio.send(channelContext, WebSocketResponse.fromText(json, VoiceAgentConst.CHARSET));
        break;
      default:
        break;
      }
      return null;
    }

    if (bridge == null) {
      String respJson = toJson(new WsVoiceAgentResponseMessage(WsVoiceAgentType.ERROR.name(), "no bridge"));
      Tio.send(channelContext, WebSocketResponse.fromText(respJson, VoiceAgentConst.CHARSET));
      return null;
    }

    try {
      if (msg != null && msg.getType() != null) {
        String typeStr = msg.getType().trim().toUpperCase();
        WsVoiceAgentType typeEnum = null;
        try {
          typeEnum = WsVoiceAgentType.valueOf(typeStr);
        } catch (Exception ex) {
          // 未识别的 type，降级处理
          log.debug("未知的 type: {}", typeStr);
        }

        if (typeEnum != null) {
          switch (typeEnum) {
          case AUDIO_END:
            bridge.endAudioInput();
            break;

          case TEXT:
            String userText = msg.getText() == null ? "" : msg.getText();
            bridge.sendText(userText);
            break;

          case CLOSE:
            bridge.close();
            Tio.remove(channelContext, "client requested close");
            break;

          default:
            // 其它类型：回显原始 JSON
            Tio.send(channelContext, WebSocketResponse.fromText(
                toJson(new WsVoiceAgentResponseMessage(WsVoiceAgentType.IGNORED.name(), t)), VoiceAgentConst.CHARSET));
            break;
          }
        }
      }
    } catch (Exception e) {
      log.error(e.getMessage(), e);
    }
    return null;
  }

  private String toJson(WsVoiceAgentResponseMessage wsVoiceAgentResponseMessage) {
    return JsonUtils.toSkipNullJson(wsVoiceAgentResponseMessage);
  }

  private void connectLLM(ChannelContext channelContext, String platform, RealtimeSetup setup) {
    String k = ChannelContextUtils.key(channelContext);

    // 启动 recorder（用户是 16k，模型默认 24k）
    try {
      SessionAudioRecorder.start(k, 16000, 24000);
    } catch (Exception e) {
      log.warn("start recorder failed: {}", e.getMessage());
    }

    RealtimeBridgeCallback callback = new WsRealtimeBridgeCallback(channelContext);
    callback.start(setup);
    RealtimeModelBridge bridge = RealtimeModelBridgeFactory.createBridge(platform, callback);
    BRIDGES.put(k, bridge);
    // 连接 Gemini Live（异步）
    bridge.connect(setup);
  }

}