package com.litongjava.voice.agent.bridge;

public interface RealtimeBridgeCallback {
  void sendText(String json);

  void sendBinary(byte[] bytes);
  
  void sendAudio(String turnId, String audioBase64);

  void close(String reason);

  void session(String sessionId);

  void turnComplete(String role, String text);

  void start(RealtimeSetup setup);

  void onUserAudioActivity();

  void onUserTextActivity(String text);
}