package com.litongjava.voice.agent.bridge;

import java.util.concurrent.CompletableFuture;

/**
 * 统一抽象：任何“实时语音/音频”模型桥接都实现这个接口。
 * VoiceSocketHandler 只依赖此接口。
 */
public interface RealtimeModelBridge {

  //public RealtimeModelBridge(RealtimeBridgeCallback sender,String url,String model,String voiceName)

  /**
   * 建立到模型的会话连接，并完成必要的 session/setup 配置。
   */
  CompletableFuture<Void> connect(RealtimeSetup setup);

  /**
   * 发送一段上行音频（浏览器推来的 PCM16 16kHz mono little-endian）。
   */
  CompletableFuture<Void> sendPcm16k(byte[] pcm16k);

  /**
   * 结束当前音频输入/触发模型生成：
   * - Gemini：sendAudioStreamEnd()
   * - Qwen Manual：commit + response.create
   * - Qwen Server VAD：可以是 no-op（或作为手动触发的补充）
   */
  CompletableFuture<Void> endAudioInput();

  /**
   * 可选：发送文本输入（聊天框）。
   */
  CompletableFuture<Void> sendText(String text);

  /**
   * 关闭会话并释放资源。
   */
  CompletableFuture<Void> close();
}