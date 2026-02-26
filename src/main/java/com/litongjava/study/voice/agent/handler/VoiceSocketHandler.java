package com.litongjava.study.voice.agent.handler;



import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.litongjava.study.voice.agent.bridge.BridgeFrontendSender;
import com.litongjava.study.voice.agent.bridge.GeminiLiveBridge;
import com.litongjava.study.voice.agent.model.WsVoiceAgentRequestMessage;
import com.litongjava.study.voice.agent.model.WsVoiceAgentResponseMessage;
import com.litongjava.study.voice.agent.model.WsVoiceAgentType;
import com.litongjava.tio.core.ChannelContext;
import com.litongjava.tio.core.Tio;
import com.litongjava.tio.http.common.HttpRequest;
import com.litongjava.tio.http.common.HttpResponse;
import com.litongjava.tio.utils.json.JsonUtils;
import com.litongjava.tio.websocket.common.WebSocketRequest;
import com.litongjava.tio.websocket.common.WebSocketResponse;
import com.litongjava.tio.websocket.common.WebSocketSessionContext;
import com.litongjava.tio.websocket.server.handler.IWebSocketHandler;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class VoiceSocketHandler implements IWebSocketHandler {
  public static final String CHARSET = "utf-8";

  // 一个前端连接一个 bridge
  private static final Map<String, GeminiLiveBridge> BRIDGES = new ConcurrentHashMap<>();

  private String key(ChannelContext ctx) {
    // 用 tio 自己的唯一标识
    return ctx.getId();
  }

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
    String k = key(channelContext);
    GeminiLiveBridge bridge = BRIDGES.remove(k);
    if (bridge != null) {
      bridge.close();
    }
    Tio.remove(channelContext, "客户端主动关闭连接");
    return null;
  }

  @Override
  public Object onBytes(WebSocketRequest wsRequest, byte[] bytes, ChannelContext channelContext) throws Exception {
    // 前端推：16k PCM mono 裸流
    GeminiLiveBridge bridge = BRIDGES.get(key(channelContext));
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
    GeminiLiveBridge bridge = BRIDGES.get(key(channelContext));

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
        String systemPrompt = msg.getSystem_prompt() == null ? "" : msg.getSystem_prompt();
        String userPrompt = msg.getUser_prompt() == null ? "" : msg.getUser_prompt();
        connectLLM(channelContext, systemPrompt, userPrompt);
        // 回显确认
        String json = toJson(new WsVoiceAgentResponseMessage(WsVoiceAgentType.SETUP_RECEIVED.name()));
        Tio.send(channelContext, WebSocketResponse.fromText(json, CHARSET));
        break;
      default:
        break;
      }
      return null;
    }

    if (bridge == null) {
      String respJson = toJson(new WsVoiceAgentResponseMessage(WsVoiceAgentType.ERROR.name(), "no bridge"));
      Tio.send(channelContext, WebSocketResponse.fromText(respJson, CHARSET));
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
            bridge.sendAudioStreamEnd();
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
            Tio.send(channelContext, WebSocketResponse
                .fromText(toJson(new WsVoiceAgentResponseMessage(WsVoiceAgentType.IGNORED.name(), t)), CHARSET));
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

  private void connectLLM(ChannelContext channelContext, String systemPrompt, String userPrompt) {
    String k = key(channelContext);

    BridgeFrontendSender sender = new BridgeFrontendSender() {
      @Override
      public void sendText(String json) {
        WebSocketResponse wsResp = WebSocketResponse.fromText(json, CHARSET);
        Tio.send(channelContext, wsResp);
      }

      @Override
      public void sendBinary(byte[] bytes) {
        WebSocketResponse wsResp = WebSocketResponse.fromBytes(bytes);
        Tio.send(channelContext, wsResp);
      }

      @Override
      public void close(String reason) {
        Tio.remove(channelContext, reason);
      }
    };

    GeminiLiveBridge bridge = new GeminiLiveBridge(sender);
    bridge.setPrompts(systemPrompt, userPrompt);
    BRIDGES.put(k, bridge);
    // 连接 Gemini Live（异步）
    bridge.connect();
  }
}