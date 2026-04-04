package nexus.io.voice.agent.config;

import java.util.Optional;

import com.google.genai.ApiClient;

import lombok.extern.slf4j.Slf4j;
import nexus.io.context.BootConfiguration;
import nexus.io.tio.boot.admin.config.TioAdminControllerConfiguration;
import nexus.io.tio.boot.admin.config.TioAdminDbConfiguration;
import nexus.io.tio.boot.admin.config.TioAdminHandlerConfiguration;
import nexus.io.tio.boot.admin.config.TioAdminInterceptorConfiguration;
import nexus.io.tio.boot.admin.config.TioAdminMongoDbConfiguration;
import nexus.io.tio.boot.admin.config.TioAdminRedisDbConfiguration;
import nexus.io.tio.boot.server.TioBootServer;
import nexus.io.tio.boot.websocket.WebSocketRouter;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.voice.agent.handler.VoiceSocketHandler;

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
