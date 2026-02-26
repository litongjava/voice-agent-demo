package com.litongjava.study.voice.agent.bridge;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.google.genai.AsyncSession;
import com.google.genai.Client;
import com.google.genai.types.ActivityHandling;
import com.google.genai.types.AudioTranscriptionConfig;
import com.google.genai.types.AutomaticActivityDetection;
import com.google.genai.types.Blob;
import com.google.genai.types.ClientOptions;
import com.google.genai.types.Content;
import com.google.genai.types.EndSensitivity;
import com.google.genai.types.LiveConnectConfig;
import com.google.genai.types.LiveSendClientContentParameters;
import com.google.genai.types.LiveSendRealtimeInputParameters;
import com.google.genai.types.LiveServerContent;
import com.google.genai.types.LiveServerMessage;
import com.google.genai.types.Modality;
import com.google.genai.types.Part;
import com.google.genai.types.PrebuiltVoiceConfig;
import com.google.genai.types.RealtimeInputConfig;
import com.google.genai.types.SpeechConfig;
import com.google.genai.types.StartSensitivity;
import com.google.genai.types.ThinkingConfig;
import com.google.genai.types.TurnCoverage;
import com.google.genai.types.VoiceConfig;
import com.litongjava.gemini.GeminiClient;
import com.litongjava.study.voice.agent.model.WsVoiceAgentResponseMessage;
import com.litongjava.tio.utils.json.JsonUtils;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GeminiLiveBridge {

  private static final String MODEL = "models/gemini-2.5-flash-native-audio-preview-12-2025";

  // 输入输出音频 mime
  private static final String INPUT_MIME = "audio/pcm;rate=16000";
  private static final String OUTPUT_MIME_PREFIX = "audio/pcm"; // 输出是 audio/pcm（24k）

  private final Client client;
  private volatile AsyncSession session;
  private final BridgeFrontendSender sender;

  // 存储 prompts
  private volatile String systemPrompt = "";
  private volatile String userPrompt = "";

  public GeminiLiveBridge(BridgeFrontendSender sender) {
    this.sender = sender;

    Client.Builder b = Client.builder().apiKey(GeminiClient.GEMINI_API_KEY);

    ClientOptions clientOptions = ClientOptions.builder().build();

    b.clientOptions(clientOptions);

    this.client = b.build();
  }

  public CompletableFuture<Void> connect() {
    LiveConnectConfig config = buildLiveConfig();

    // AsyncLive.connect(model, config) -> CompletableFuture<AsyncSession>
    return client.async.live.connect(MODEL, config).thenCompose(sess -> {
      this.session = sess;
      send(new WsVoiceAgentResponseMessage("gemini_connected", sess.sessionId()));

      // 如果连接建立时已存在 prompts（可能前端先发 setup），立即发送到模型
      try {
        sendPromptsIfAny(sess);
      } catch (Exception ex) {
        log.error("send setup prompts error (connect)", ex);
        send(new WsVoiceAgentResponseMessage("error", safe(ex.getMessage())));
      }

      // 注册 receive 回调（只注册一次）
      return sess.receive(this::onGeminiMessage);
    }).exceptionally(ex -> {
      log.error("Gemini live connect failed", ex);
      send(new WsVoiceAgentResponseMessage("error", safe(ex.getMessage())));
      sender.close("gemini connect failed");
      return null;
    });
  }

  public CompletableFuture<Void> close() {
    try {
      AsyncSession s = this.session;
      if (s != null) {
        return s.close().exceptionally(ex -> null);
      }
    } finally {
      try {
        client.close();
      } catch (Exception ignore) {
      }
    }
    return CompletableFuture.completedFuture(null);
  }

  /** 前端推来的 16k PCM 裸流 */
  public CompletableFuture<Void> sendPcm16k(byte[] pcm16k) {
    AsyncSession s = this.session;
    if (s == null)
      return CompletableFuture.completedFuture(null);

    Blob audioBlob = Blob.builder().mimeType(INPUT_MIME).data(pcm16k).build();

    LiveSendRealtimeInputParameters params = LiveSendRealtimeInputParameters.builder().audio(audioBlob).build();

    return s.sendRealtimeInput(params).exceptionally(ex -> {
      send(new WsVoiceAgentResponseMessage("error", safe(ex.getMessage())));
      return null;
    });
  }

  /** 前端说“音频结束”（可选，用于提示流结束） */
  public CompletableFuture<Void> sendAudioStreamEnd() {
    AsyncSession s = this.session;
    if (s == null)
      return CompletableFuture.completedFuture(null);

    LiveSendRealtimeInputParameters params = LiveSendRealtimeInputParameters.builder().audioStreamEnd(true).build();

    return s.sendRealtimeInput(params).exceptionally(ex -> {
      send(new WsVoiceAgentResponseMessage("error", safe(ex.getMessage())));
      return null;
    });
  }

  /** 前端发文本输入（可选） */
  public CompletableFuture<Void> sendText(String text) {
    AsyncSession s = this.session;
    if (s == null)
      return CompletableFuture.completedFuture(null);

    Content userMessage = Content.fromParts(Part.fromText(text));

    LiveSendClientContentParameters cc = LiveSendClientContentParameters.builder().turns(List.of(userMessage))
        .turnComplete(true).build();

    return s.sendClientContent(cc).exceptionally(ex -> {
      send(new WsVoiceAgentResponseMessage("error", safe(ex.getMessage())));
      return null;
    });
  }

  // ---------------- internal ----------------

  public void setPrompts(String systemPrompt, String userPrompt) {
    this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
    this.userPrompt = userPrompt == null ? "" : userPrompt;
    log.info("Prompts set: system.len={} user.len={}", this.systemPrompt.length(), this.userPrompt.length());

    // 如果 session 已经建立，则立即发送到模型（处理前端先 connect 后 send setup 的情况）
    AsyncSession s = this.session;
    if (s != null) {
      try {
        sendPromptsIfAny(s);
      } catch (Exception ex) {
        log.error("send setup prompts error (in setPrompts)", ex);
        send(new WsVoiceAgentResponseMessage("error", safe(ex.getMessage())));
      }
    }
  }

  private void sendPromptsIfAny(AsyncSession s) {
    if ((systemPrompt != null && !systemPrompt.isEmpty()) || (userPrompt != null && !userPrompt.isEmpty())) {
      List<Content> initialTurns = new java.util.ArrayList<>();
      if (systemPrompt != null && !systemPrompt.isEmpty()) {
        initialTurns.add(Content.fromParts(Part.fromText(systemPrompt)));
      }
      if (userPrompt != null && !userPrompt.isEmpty()) {
        initialTurns.add(Content.fromParts(Part.fromText(userPrompt)));
      }

      if (!initialTurns.isEmpty()) {
        LiveSendClientContentParameters cc = LiveSendClientContentParameters.builder().turns(initialTurns)
            // 初始化指令通常作为 context 而非单次完成的用户 turn，你可按需调整 turnComplete
            .turnComplete(false).build();

        s.sendClientContent(cc).exceptionally(ex -> {
          send(new WsVoiceAgentResponseMessage("error", safe(ex.getMessage())));
          return null;
        });

        send(new WsVoiceAgentResponseMessage("setup_sent_to_model"));
      }
    }
  }

  private LiveConnectConfig buildLiveConfig() {
    // 自动VAD配置：AutomaticActivityDetection/RealtimeInputConfig
    AutomaticActivityDetection vad = AutomaticActivityDetection.builder().disabled(false)
        .startOfSpeechSensitivity(StartSensitivity.Known.START_SENSITIVITY_HIGH)
        .endOfSpeechSensitivity(EndSensitivity.Known.END_SENSITIVITY_LOW).prefixPaddingMs(100).silenceDurationMs(500)
        .build();

    RealtimeInputConfig realtimeInput = RealtimeInputConfig.builder().automaticActivityDetection(vad)
        .activityHandling(ActivityHandling.Known.START_OF_ACTIVITY_INTERRUPTS)
        .turnCoverage(TurnCoverage.Known.TURN_INCLUDES_ONLY_ACTIVITY).build();

    PrebuiltVoiceConfig prebuiltVoiceConfig = PrebuiltVoiceConfig.builder().voiceName("Puck").build();
    VoiceConfig voiceConfig = VoiceConfig.builder().prebuiltVoiceConfig(prebuiltVoiceConfig).build();
    SpeechConfig speech = SpeechConfig.builder().voiceConfig(voiceConfig).build();

    ThinkingConfig thinkingConfig = ThinkingConfig.builder().thinkingBudget(0).build();
    AudioTranscriptionConfig inputAudioTranscription = AudioTranscriptionConfig.builder().build();
    LiveConnectConfig liveConnectConfig = LiveConnectConfig.builder()
        .responseModalities(List.of(new Modality(Modality.Known.AUDIO)))
        //
        .speechConfig(speech).thinkingConfig(thinkingConfig).realtimeInputConfig(realtimeInput)
        // 可选：转写
        .inputAudioTranscription(inputAudioTranscription).outputAudioTranscription(inputAudioTranscription)
        //
        .build();

    return liveConnectConfig;
  }

  private void onGeminiMessage(LiveServerMessage msg) {
    try {
      if (msg.setupComplete().isPresent()) {
        send(new WsVoiceAgentResponseMessage("setup_complete"));
      }

      Optional<LiveServerContent> serverContentOpt = msg.serverContent();
      if (serverContentOpt.isPresent()) {
        LiveServerContent sc = serverContentOpt.get();

        // 输入转写
        sc.inputTranscription().ifPresent(t -> send(new WsVoiceAgentResponseMessage("transcript_in", t.text())));

        // 输出转写
        sc.outputTranscription().ifPresent(t -> send(new WsVoiceAgentResponseMessage("transcript_out", t.text())));

        // 模型输出（音频/文本 part）
        sc.modelTurn().ifPresent(content -> {
          content.parts().ifPresent(parts -> {
            for (Part p : parts) {
              // 文本
              p.text().ifPresent(txt -> send(new WsVoiceAgentResponseMessage("text", txt)));

              // 音频（inlineData）
              p.inlineData().ifPresent(blob -> {
                String mt = blob.mimeType().orElse("");
                byte[] data = blob.data().orElse(null);
                if (data != null && mt.startsWith(OUTPUT_MIME_PREFIX)) {
                  // 直接二进制回传（建议前端按 24k PCM 播放）
                  sender.sendBinary(data);
                } else if (data != null) {
                  // 兜底：非音频inlineData，走base64文本
                  String b64 = Base64.getEncoder().encodeToString(data);
                  WsVoiceAgentResponseMessage m = new WsVoiceAgentResponseMessage("inline_data");
                  m.setText(b64);
                  send(m);
                }
              });

              // functionCall 等你后续再扩展
              p.functionCall().ifPresent(fc -> {
                WsVoiceAgentResponseMessage m = new WsVoiceAgentResponseMessage("function_call");
                m.setText(fc.name().orElse(""));
                send(m);
              });
            }
          });
        });

        // turnComplete
        if (sc.turnComplete().orElse(false)) {
          send(new WsVoiceAgentResponseMessage("turn_complete"));
        }
      }

      // goAway（服务端提示将断开）
      msg.goAway().ifPresent(g -> {
        String timeLeft = g.timeLeft().map(Duration::toString) // 例如 PT30S / PT1M
            .orElse("");
        WsVoiceAgentResponseMessage m = new WsVoiceAgentResponseMessage("go_away");
        m.setText(timeLeft);
        send(m);
      });

      // usage
      msg.usageMetadata().ifPresent(u -> {
        WsVoiceAgentResponseMessage m = new WsVoiceAgentResponseMessage("usage");
        // 将 usage 信息编码到 text 字段或扩展 DTO；此处把三个数字拼接（可按需改成专门字段）
        Optional<Integer> promptTokenCount = u.promptTokenCount();
        Optional<Integer> responseTokenCount = u.responseTokenCount();
        Optional<Integer> totalTokenCount = u.totalTokenCount();
        m.setPromptTokenCount(promptTokenCount);
        m.setResponseTokenCount(responseTokenCount);
        m.setTotalTokenCount(totalTokenCount);
        send(m);
      });

    } catch (Exception e) {
      log.error("onGeminiMessage error", e);
      send(new WsVoiceAgentResponseMessage("error", safe(e.getMessage())));
    }
  }

  private void send(WsVoiceAgentResponseMessage msg) {
    try {
      String json = JsonUtils.toSkipNullJson(msg);
      sender.sendText(json);
    } catch (Exception e) {
      log.error("serialize message error", e);
      // fallback minimal message
//      WsVoiceAgentResponseMessage wsVoiceAgentResponseMessage = new WsVoiceAgentResponseMessage();
//      wsVoiceAgentResponseMessage.setType(WsVoiceAgentType.ERROR.name());
//      wsVoiceAgentResponseMessage.setMessage("serialize error");
      // 手写性能高
      sender.sendText("{\"type\":\"error\",\"message\":\"serialize error\"}");
    }
  }

  private static String safe(String s) {
    if (s == null)
      return "";
    return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
  }

  private static String safe(Object o) {
    return safe(o == null ? "" : String.valueOf(o));
  }
}