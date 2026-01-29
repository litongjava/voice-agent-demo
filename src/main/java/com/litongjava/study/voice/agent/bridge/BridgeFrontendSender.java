package com.litongjava.study.voice.agent.bridge;

// 由外层注入：把“后端要发回前端”的动作抽象成一个 sender
public interface BridgeFrontendSender {
  public void sendText(String json);

  public void sendBinary(byte[] bytes);

  public void close(String reason);
}