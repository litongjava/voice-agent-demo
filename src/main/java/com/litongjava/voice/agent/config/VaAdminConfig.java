package com.litongjava.voice.agent.config;

import java.util.Optional;

import com.google.genai.ApiClient;
import com.litongjava.context.BootConfiguration;
import com.litongjava.tio.boot.admin.config.TioAdminControllerConfiguration;
import com.litongjava.tio.boot.admin.config.TioAdminDbConfiguration;
import com.litongjava.tio.boot.admin.config.TioAdminHandlerConfiguration;
import com.litongjava.tio.boot.admin.config.TioAdminInterceptorConfiguration;
import com.litongjava.tio.boot.admin.config.TioAdminMongoDbConfiguration;
import com.litongjava.tio.boot.admin.config.TioAdminRedisDbConfiguration;
import com.litongjava.tio.boot.admin.handler.system.SystemFileTencentCosHandler;
import com.litongjava.tio.boot.server.TioBootServer;
import com.litongjava.tio.boot.websocket.WebSocketRouter;
import com.litongjava.tio.http.server.router.HttpRequestRouter;
import com.litongjava.tio.utils.environment.EnvUtils;
import com.litongjava.voice.agent.handler.LiveKitTokenHandler;
import com.litongjava.voice.agent.handler.VoiceSocketHandler;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class VaAdminConfig implements BootConfiguration {

  public void config() {

    TioBootServer server = TioBootServer.me();
    // 配置数据库相关
    new TioAdminDbConfiguration().config();
    new TioAdminRedisDbConfiguration().config();
    new TioAdminMongoDbConfiguration().config();
    configInterceptor();
    configHandler(server);
    configWebSocket(server);
    configGeminiProxy();

//    UdpServerConf udpServerConf = new UdpServerConf(30000, new RtpEchoUdpHandler(), 5000);
//    UdpServer udpServer;
//    try {
//      udpServer = new UdpServer(udpServerConf);
//      udpServer.start();
//    } catch (SocketException e) {
//      e.printStackTrace();
//    }

  }

  private void configInterceptor() {
    String[] permitUrl = { "/api/v1/voice/agent", "/api/v1/livekit/token" };
    new TioAdminInterceptorConfiguration(permitUrl).config();
  }

  private void configHandler(TioBootServer server) {
    new TioAdminHandlerConfiguration().config();

    // 获取 HTTP 请求路由器
    HttpRequestRouter r = server.getRequestRouter();
    if (r != null) {
      r.add("/api/v1/livekit/token", new LiveKitTokenHandler());
    }

    // 配置控制器
    new TioAdminControllerConfiguration().config();
  }

  private void configWebSocket(TioBootServer server) {
    WebSocketRouter webSocketRouter = server.getWebSocketRouter();
    if (webSocketRouter != null) {
      VoiceSocketHandler voiceSocketHandler = new VoiceSocketHandler();
      webSocketRouter.add("/api/v1/voice/agent", voiceSocketHandler);
    }
  }

  private void configGeminiProxy() {
    String geminiBaseUrlStr = EnvUtils.getStr("GOOGLE_GEMINI_BASE_URL");
    if (geminiBaseUrlStr != null) {
      log.info("GOOGLE_GEMINI_BASE_URL:{}", geminiBaseUrlStr);
      String vertixBaseUrlStr = EnvUtils.getStr("GOOGLE_VERTEX_BASE_URL");

      if (vertixBaseUrlStr != null) {
        Optional<String> geminiBaseUrl = Optional.of(geminiBaseUrlStr);
        Optional<String> vertexBaseUrl = Optional.of(vertixBaseUrlStr);
        ApiClient.setDefaultBaseUrls(geminiBaseUrl, vertexBaseUrl);
      }
    }
  }
}
