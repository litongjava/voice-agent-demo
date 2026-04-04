package nexus.io.voice.agent.bridge;

import nexus.io.consts.ModelPlatformName;
import nexus.io.tio.utils.environment.EnvUtils;
import nexus.io.voice.agent.bridge.RealtimeBridgeCallback;
import nexus.io.voice.agent.bridge.RealtimeModelBridge;

public class RealtimeModelBridgeFactory {

  public static RealtimeModelBridge createBridge(String platform, RealtimeBridgeCallback callback) {
    if (platform == null) {
      platform = EnvUtils.getStr("vioce.agent.platform");
    }
    RealtimeModelBridge bridge = null;
    if (ModelPlatformName.GOOGLE.equals(platform)) {
      bridge = new GoogleGeminiRealtimeBridge(callback);

    } else if (ModelPlatformName.BAILIAN.equals(platform)) {
      bridge = new QwenOmniRealtimeBridge(callback);

    } else if (ModelPlatformName.ELEVEN_LABS.equals(platform)) {

    } else {
      bridge = new QwenOmniRealtimeBridge(callback);
    }
    return bridge;
  }
}
