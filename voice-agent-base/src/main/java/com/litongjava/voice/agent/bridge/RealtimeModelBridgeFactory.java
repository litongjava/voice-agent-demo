package com.litongjava.voice.agent.bridge;

import com.litongjava.consts.ModelPlatformName;
import com.litongjava.tio.utils.environment.EnvUtils;

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
