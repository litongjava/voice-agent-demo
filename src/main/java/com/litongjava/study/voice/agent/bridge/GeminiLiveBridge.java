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
    return client.async.live.connect(MODEL, config) // :contentReference[oaicite:7]{index=7}
        .thenCompose(sess -> {
          this.session = sess;
          sender.sendText("{\"type\":\"gemini_connected\",\"sessionId\":\"" + safe(sess.sessionId()) + "\"}");

          // 注册 receive 回调（只注册一次） :contentReference[oaicite:8]{index=8}
          return sess.receive(this::onGeminiMessage);
        }).exceptionally(ex -> {
          log.error("Gemini live connect failed", ex);
          sender.sendText("{\"type\":\"error\",\"where\":\"connect\",\"message\":\"" + safe(ex.getMessage()) + "\"}");
          sender.close("gemini connect failed");
          return null;
        });
  }

  public CompletableFuture<Void> close() {
    try {
      AsyncSession s = this.session;
      if (s != null) {
        return s.close().exceptionally(ex -> null); // :contentReference[oaicite:9]{index=9}
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

    Blob audioBlob = Blob.builder().mimeType(INPUT_MIME).data(pcm16k).build(); // :contentReference[oaicite:10]{index=10}

    LiveSendRealtimeInputParameters params = LiveSendRealtimeInputParameters.builder().audio(audioBlob).build(); // :contentReference[oaicite:11]{index=11}

    return s.sendRealtimeInput(params) // :contentReference[oaicite:12]{index=12}
        .exceptionally(ex -> {
          sender.sendText(
              "{\"type\":\"error\",\"where\":\"sendRealtimeInput\",\"message\":\"" + safe(ex.getMessage()) + "\"}");
          return null;
        });
  }

  /** 前端说“音频结束”（可选，用于提示流结束） */
  public CompletableFuture<Void> sendAudioStreamEnd() {
    AsyncSession s = this.session;
    if (s == null)
      return CompletableFuture.completedFuture(null);

    LiveSendRealtimeInputParameters params = LiveSendRealtimeInputParameters.builder().audioStreamEnd(true).build(); // :contentReference[oaicite:13]{index=13}

    return s.sendRealtimeInput(params).exceptionally(ex -> null); // :contentReference[oaicite:14]{index=14}
  }

  /** 前端发文本输入（可选） */
  public CompletableFuture<Void> sendText(String text) {
    AsyncSession s = this.session;
    if (s == null)
      return CompletableFuture.completedFuture(null);

    Content userMessage = Content.fromParts(Part.fromText(text));

    LiveSendClientContentParameters cc = LiveSendClientContentParameters.builder().turns(List.of(userMessage))
        .turnComplete(true).build();

    return s.sendClientContent(cc).exceptionally(ex -> null);
  }

  // ---------------- internal ----------------

  private LiveConnectConfig buildLiveConfig() {
    // 自动VAD配置：AutomaticActivityDetection/RealtimeInputConfig
    // :contentReference[oaicite:16]{index=16}
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

    // LiveConnectConfig：responseModalities / speechConfig / thinkingConfig /
    // realtimeInputConfig 等字段在 Javadoc 中列出 :contentReference[oaicite:17]{index=17}
    com.google.genai.types.LiveConnectConfig.Builder builder = LiveConnectConfig.builder();
    Modality modality = new Modality(Modality.Known.AUDIO);
    List<Modality> modalities = List.of(modality);

    ThinkingConfig thinkingConfig = ThinkingConfig.builder().thinkingBudget(0).build();
    AudioTranscriptionConfig inputAudioTranscription = AudioTranscriptionConfig.builder().build();
    LiveConnectConfig liveConnectConfig = builder.responseModalities(modalities)
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
        sender.sendText("{\"type\":\"setup_complete\"}");
      }

      Optional<LiveServerContent> serverContentOpt = msg.serverContent();
      if (serverContentOpt.isPresent()) {
        LiveServerContent sc = serverContentOpt.get();

        // 输入转写
        sc.inputTranscription()
            .ifPresent(t -> sender.sendText("{\"type\":\"transcript_in\",\"text\":\"" + safe(t.text()) + "\"}"));

        // 输出转写
        sc.outputTranscription()
            .ifPresent(t -> sender.sendText("{\"type\":\"transcript_out\",\"text\":\"" + safe(t.text()) + "\"}"));

        // 模型输出（音频/文本 part）
        sc.modelTurn().ifPresent(content -> {
          content.parts().ifPresent(parts -> {
            for (Part p : parts) {
              // 文本
              p.text().ifPresent(txt -> sender.sendText("{\"type\":\"text\",\"text\":\"" + safe(txt) + "\"}"));

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
                  sender.sendText(
                      "{\"type\":\"inline_data\",\"mimeType\":\"" + safe(mt) + "\",\"data\":\"" + b64 + "\"}");
                }
              });

              // functionCall 等你后续再扩展
              p.functionCall().ifPresent(fc -> {
                sender.sendText("{\"type\":\"function_call\",\"name\":\"" + safe(fc.name().orElse("")) + "\"}");
              });
            }
          });
        });

        // turnComplete
        if (sc.turnComplete().orElse(false)) {
          sender.sendText("{\"type\":\"turn_complete\"}");
        }
      }

      // goAway（服务端提示将断开）

      msg.goAway().ifPresent(g -> {
        String timeLeft = g.timeLeft().map(Duration::toString) // 例如 PT30S / PT1M
            .orElse("");
        sender.sendText("{\"type\":\"go_away\",\"timeLeft\":\"" + safe(timeLeft) + "\"}");
      });

      // usage
      msg.usageMetadata().ifPresent(u -> sender.sendText("{\"type\":\"usage\",\"prompt\":" + u.promptTokenCount()
          + ",\"response\":" + u.responseTokenCount() + ",\"total\":" + u.totalTokenCount() + "}"));

    } catch (Exception e) {
      log.error("onGeminiMessage error", e);
      sender
          .sendText("{\"type\":\"error\",\"where\":\"onGeminiMessage\",\"message\":\"" + safe(e.getMessage()) + "\"}");
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
