package com.litongjava.voice.agent.bridge;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import com.alibaba.dashscope.audio.omni.OmniRealtimeCallback;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConfig;
import com.alibaba.dashscope.audio.omni.OmniRealtimeConversation;
import com.alibaba.dashscope.audio.omni.OmniRealtimeModality;
import com.alibaba.dashscope.audio.omni.OmniRealtimeParam;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.google.gson.JsonObject;
import com.litongjava.tio.utils.environment.EnvUtils;
import com.litongjava.tio.utils.hutool.StrUtil;
import com.litongjava.tio.utils.json.JsonUtils;
import com.litongjava.voice.agent.model.WsVoiceAgentResponseMessage;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class QwenOmniRealtimeBridge implements RealtimeModelBridge {

  // 中国区（北京）
  private String url = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime";
  private String model = "qwen3-omni-flash-realtime";
  private String voiceName = "Cherry";

  private final RealtimeBridgeCallback callback;

  private volatile OmniRealtimeConversation conversation;
  private final AtomicBoolean connected = new AtomicBoolean(false);

  // 你前端上行是 16k PCM16；Qwen3-Omni-Flash-Realtime 下行通常是 24k PCM16（pcm24）
  // 注意：DashScope 事件里音频是 base64；你仍可给浏览器发 bytes（二进制）
  public QwenOmniRealtimeBridge(RealtimeBridgeCallback callback) {
    this.callback = callback;
  }

  public QwenOmniRealtimeBridge(RealtimeBridgeCallback callback, String url, String model, String voiceName) {
    this.callback = callback;
    if (url != null) {
      this.url = url;
    }
    if (model != null) {
      this.model = model;
    }

    if (voiceName != null) {
      this.voiceName = voiceName;
    }
  }

  public CompletableFuture<Void> connect(RealtimeSetup setup) {
    return CompletableFuture.runAsync(() -> {
      try {
        String apiKey = EnvUtils.getStr("DASHSCOPE_API_KEY");
        if (StrUtil.isBlank(apiKey)) {
          throw new IllegalStateException("DASHSCOPE_API_KEY is empty");
        }

        OmniRealtimeParam param = OmniRealtimeParam.builder().model(model).apikey(apiKey).url(url).build();

        this.conversation = new OmniRealtimeConversation(param, new OmniRealtimeCallback() {
          @Override
          public void onOpen() {
            connected.set(true);
            sendJson(new WsVoiceAgentResponseMessage("qwen_connected", model));
          }

          @Override
          public void onClose(int code, String reason) {
            connected.set(false);
            sendJson(new WsVoiceAgentResponseMessage("close", reason));
            callback.close("dashscope closed: " + code + ", " + reason);
          }

          @Override
          public void onEvent(JsonObject event) {
            handleEvent(event);
          }
        });

        conversation.connect();

        // 会话配置：建议默认 server_vad + text/audio + transcription
        OmniRealtimeConfig cfg = buildSessionConfig(setup);
        conversation.updateSession(cfg);

        sendJson(new WsVoiceAgentResponseMessage("setup_sent_to_model"));

      } catch (NoApiKeyException e) {
        log.error("NoApiKeyException", e);
        sendError("no_api_key", e.getMessage());
        callback.close("no api key");
      } catch (Exception e) {
        log.error("connect error", e);
        sendError("connect_error", e.getMessage());
        callback.close("connect failed");
      }
    });
  }

  public CompletableFuture<Void> close() {
    return CompletableFuture.runAsync(() -> {
      try {
        OmniRealtimeConversation c = this.conversation;
        if (c != null) {
          c.close(1000, "server close");
        }
      } catch (Exception ignore) {
      } finally {
        connected.set(false);
        callback.close("close");
      }
    });
  }

  /**
   * 浏览器推来的 16k PCM16 bytes（little-endian） DashScope 需要 base64 后通过
   * input_audio_buffer.append 事件发送（SDK 封装为 appendAudio）。
   */
  public CompletableFuture<Void> sendPcm16k(byte[] pcm16k) {
    return CompletableFuture.runAsync(() -> {
      OmniRealtimeConversation c = this.conversation;
      if (c == null || !connected.get() || pcm16k == null || pcm16k.length == 0) {
        return;
      }
      try {
        String b64 = Base64.getEncoder().encodeToString(pcm16k);
        c.appendAudio(b64);
      } catch (Exception e) {
        log.error("appendAudio failed", e);
        sendError("append_audio_failed", e.getMessage());
      }
    });
  }

  /**
   * Manual 模式：把你现有的 audio_end 映射为 commit + createResponse 如果你使用
   * server_vad，可不调用这个方法。
   */
  public CompletableFuture<Void> commitAndCreateResponse() {
    return CompletableFuture.runAsync(() -> {
      OmniRealtimeConversation c = this.conversation;
      if (c == null || !connected.get())
        return;
      try {
        c.commit();
        c.createResponse(null, null);
      } catch (Exception e) {
        log.error("commit/createResponse failed", e);
        sendError("commit_failed", e.getMessage());
      }
    });
  }

  /**
   * 文本输入：SDK 里有的版本支持发文本项；若你只做语音助手，可先不实现。 如果你确实要支持“输入框发文本”，建议用 Realtime
   * 的对话项事件（conversation.item.create） 或使用 SDK 提供的文本接口（视 SDK 版本而定）。
   */
  public CompletableFuture<Void> sendText(String text) {
    // 先给出最安全的行为：转为 instructions 追加或忽略
    return CompletableFuture.completedFuture(null);
  }

  private OmniRealtimeConfig buildSessionConfig(RealtimeSetup setup) {
    // 把你的 setup 组合成 instructions
    String instructions = buildInstructions(setup);

    // server_vad（通话模式）：enableTurnDetection(true)
    // manual（按下即说）：enableTurnDetection(false)，并在 audio_end 时 commit+createResponse
    boolean useServerVad = true;

    List<OmniRealtimeModality> modalities = Arrays.asList(OmniRealtimeModality.AUDIO, OmniRealtimeModality.TEXT);
    OmniRealtimeConfig.OmniRealtimeConfigBuilder b = OmniRealtimeConfig.builder()
        //
        .modalities(modalities).voice(voiceName)
        //
        .enableInputAudioTranscription(true)
        //
        .enableTurnDetection(useServerVad);
    //

    if (instructions != null) {
      b.parameters(Map.of("instructions", instructions));
    }

    return b.build();
  }

  private String buildInstructions(RealtimeSetup setup) {
    if (setup == null) {
      return null;
    }
    String system_prompt = setup.getSystem_prompt();
    String user_prompt = setup.getUser_prompt();
    String greeting = setup.getGreeting();

    StringBuilder sb = new StringBuilder();
    if (StrUtil.notBlank(system_prompt)) {
      sb.append(setup.getSystem_prompt()).append("\n");
    }

    if (StrUtil.notBlank(user_prompt)) {
      sb.append(user_prompt).append("\n");
    }

    List<SimpleChatMessage> messages = setup.getMessages();
    for (SimpleChatMessage simpleChatMessage : messages) {
      sb.append(simpleChatMessage.getMessage()).append("\n");
    }

    if (StrUtil.notBlank(greeting)) {
      sb.append(greeting).append("\n");

    }
    return sb.length() == 0 ? null : sb.toString();
  }

  private void handleEvent(JsonObject event) {
    try {
      String type = event.has("type") ? event.get("type").getAsString() : "";

      switch (type) {

      // 会话创建/更新
      case "session.created":
        sendJson(new WsVoiceAgentResponseMessage("setup_complete"));
        break;
      case "session.updated":
        // 可选：记录配置
        break;

      // 服务端 VAD 生命周期（可用于前端“打断播放”）
      case "input_audio_buffer.speech_started":
        sendJson(new WsVoiceAgentResponseMessage("speech_started"));
        break;
      case "input_audio_buffer.speech_stopped":
        sendJson(new WsVoiceAgentResponseMessage("speech_stopped"));
        break;

      // 用户输入转写完成
      case "conversation.item.input_audio_transcription.completed": {
        String transcript = event.has("transcript") ? event.get("transcript").getAsString() : "";
        sendJson(new WsVoiceAgentResponseMessage("transcript_in", transcript));
        break;
      }

      // 模型输出字幕（增量/完成）
      case "response.audio_transcript.delta": {
        String delta = event.has("delta") ? event.get("delta").getAsString() : "";
        sendJson(new WsVoiceAgentResponseMessage("transcript_out", delta));
        break;
      }
      case "response.audio_transcript.done": {
        String transcript = event.has("transcript") ? event.get("transcript").getAsString() : "";
        // 你也可以用 text 事件发完整句
        sendJson(new WsVoiceAgentResponseMessage("text", transcript));
        break;
      }

      // 模型输出音频（base64）
      case "response.audio.delta": {
        String b64 = event.has("delta") ? event.get("delta").getAsString() : "";
        if (StrUtil.notBlank(b64)) {
          byte[] pcm = Base64.getDecoder().decode(b64);
          callback.sendBinary(pcm);
        }
        break;
      }
      case "response.audio.done":
        // 音频段结束（可选）
        break;

      // 一轮完成
      case "response.done":
        sendJson(new WsVoiceAgentResponseMessage("turn_complete"));
        break;

      // 错误
      case "error":
        // 不同版本字段可能是 error/message
        sendError("remote_error", event.toString());
        break;

      default:
        // 需要排查时打开
        // sendJson(new WsVoiceAgentResponseMessage("evt", event.toString()));
        break;
      }
    } catch (Exception e) {
      log.error("handleEvent error", e);
      sendError("handle_event_error", e.getMessage());
    }
  }

  private void sendJson(WsVoiceAgentResponseMessage msg) {
    try {
      String json = JsonUtils.toSkipNullJson(msg);
      callback.sendText(json);
    } catch (Exception e) {
      callback.sendText("{\"type\":\"error\",\"message\":\"serialize error\"}");
    }
  }

  private void sendError(String where, String message) {
    WsVoiceAgentResponseMessage m = new WsVoiceAgentResponseMessage("error");
    m.setWhere(where);
    m.setMessage(message == null ? "" : message);
    sendJson(m);
  }

  @Override
  public CompletableFuture<Void> endAudioInput() {
    return CompletableFuture.completedFuture(null);
  }
}