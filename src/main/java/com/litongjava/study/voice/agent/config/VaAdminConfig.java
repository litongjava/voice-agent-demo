package com.litongjava.study.voice.agent.config;

import java.util.Optional;

import com.google.genai.ApiClient;
import com.litongjava.context.BootConfiguration;
import com.litongjava.study.voice.agent.handler.VoiceSocketHandler;
import com.litongjava.tio.boot.server.TioBootServer;
import com.litongjava.tio.boot.websocket.WebSocketRouter;
import com.litongjava.tio.utils.environment.EnvUtils;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class VaAdminConfig implements BootConfiguration {

  public void config() {

    TioBootServer server = TioBootServer.me();
//    HttpRequestRouter requestRouter = server.getRequestRouter();

//    if (requestRouter != null) {
//      HelloHandler helloHandler = new HelloHandler();
//      requestRouter.add("/hello", helloHandler::hello);
//    }
    WebSocketRouter webSocketRouter = server.getWebSocketRouter();
    if (webSocketRouter != null) {
      VoiceSocketHandler voiceSocketHandler = new VoiceSocketHandler();
      webSocketRouter.add("/api/v1/voice/agent", voiceSocketHandler);
    }

    String geminiBaseUrlStr = EnvUtils.getStr("GOOGLE_GEMINI_BASE_URL");
    if (geminiBaseUrlStr != null) {
      log.info("GOOGLE_GEMINI_BASE_URL:{}",geminiBaseUrlStr);
      String vertixBaseUrlStr = EnvUtils.getStr("GOOGLE_VERTEX_BASE_URL");
      
      if (vertixBaseUrlStr != null) {
        Optional<String> geminiBaseUrl = Optional.of(geminiBaseUrlStr);
        Optional<String> vertexBaseUrl = Optional.of(vertixBaseUrlStr);
        ApiClient.setDefaultBaseUrls(geminiBaseUrl, vertexBaseUrl);
      }

    }
  }
}
