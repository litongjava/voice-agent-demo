package com.litongjava.study.voice.agent.handler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.litongjava.study.voice.agent.bridge.BridgeFrontendSender;
import com.litongjava.study.voice.agent.bridge.GeminiLiveBridge;
import com.litongjava.tio.core.ChannelContext;
import com.litongjava.tio.core.Tio;
import com.litongjava.tio.http.common.HttpRequest;
import com.litongjava.tio.http.common.HttpResponse;
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
  public HttpResponse handshake(HttpRequest httpRequest, HttpResponse response, ChannelContext channelContext) throws Exception {
    log.info("请求信息: {}", httpRequest);
    return response;
  }

  @Override
  public void onAfterHandshaked(HttpRequest httpRequest, HttpResponse httpResponse, ChannelContext channelContext) throws Exception {
    log.info("握手完成: {}", httpRequest);
    

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
    BRIDGES.put(k, bridge);

    // 连接 Gemini Live（异步）
    bridge.connect();
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

    GeminiLiveBridge bridge = BRIDGES.get(key(channelContext));
    if (bridge == null) {
      Tio.send(channelContext, WebSocketResponse.fromText("{\"type\":\"error\",\"message\":\"no bridge\"}", CHARSET));
      return null;
    }

    // 用最简单的协议：非 JSON 就当普通文本；JSON 只识别两种
    String t = text.trim();
    if (t.startsWith("{") && t.endsWith("}")) {
      // 你可以换成 Jackson/Fastjson 解析
      if (t.contains("\"type\":\"audio_end\"")) {
        bridge.sendAudioStreamEnd();
      } else if (t.contains("\"type\":\"text\"")) {
        // 粗暴提取 text 字段演示（建议换 JSON 解析）
        String msg = extractJsonField(t, "text");
        bridge.sendText(msg == null ? "" : msg);
      } else if (t.contains("\"type\":\"close\"")) {
        bridge.close();
        Tio.remove(channelContext, "client requested close");
      } else {
        // 未识别：回显一下
        Tio.send(channelContext, WebSocketResponse.fromText("{\"type\":\"ignored\",\"raw\":" + quote(text) + "}", CHARSET));
      }
      return null;
    }

    // 非 JSON：当普通文本转发给 Gemini
    bridge.sendText(text);
    return null;
  }

  private static String extractJsonField(String json, String field) {
    // 演示用的极简实现：生产请用 JSON 库
    String key = "\"" + field + "\":";
    int idx = json.indexOf(key);
    if (idx < 0) return null;
    int start = json.indexOf('"', idx + key.length());
    if (start < 0) return null;
    int end = json.indexOf('"', start + 1);
    if (end < 0) return null;
    return json.substring(start + 1, end);
  }

  private static String quote(String s) {
    if (s == null) return "\"\"";
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
