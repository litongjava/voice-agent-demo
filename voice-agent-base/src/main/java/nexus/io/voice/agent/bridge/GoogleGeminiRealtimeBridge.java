package nexus.io.voice.agent.bridge;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.alibaba.dashscope.utils.JsonUtils;
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

import lombok.extern.slf4j.Slf4j;
import nexus.io.gemini.GeminiClient;
import nexus.io.tio.utils.hutool.StrUtil;
import nexus.io.voice.agent.bridge.RealtimeBridgeCallback;
import nexus.io.voice.agent.bridge.RealtimeModelBridge;
import nexus.io.voice.agent.bridge.RealtimeSetup;
import nexus.io.voice.agent.bridge.SimpleChatMessage;
import nexus.io.voice.agent.model.WsVoiceAgentResponseMessage;

@Slf4j
public class GoogleGeminiRealtimeBridge implements RealtimeModelBridge {

  private static final String INPUT_MIME = "audio/pcm;rate=16000";
  private static final String OUTPUT_MIME_PREFIX = "audio/pcm";

  private String model = "models/gemini-2.5-flash-native-audio-preview-12-2025";
  private String voiceName = "Puck";

  private final Object transcriptLock = new Object();
  private final StringBuilder turnUserTranscript = new StringBuilder();
  private final StringBuilder turnAssistantTranscript = new StringBuilder();

  /**
   * 协议级 turn 控制
   */
  private final Object assistantTurnLock = new Object();
  private volatile String currentAssistantTurnId;
  private volatile boolean assistantTurnOpen = false;

  private final Client client;
  private volatile AsyncSession session;
  private final RealtimeBridgeCallback callback;

  public GoogleGeminiRealtimeBridge(RealtimeBridgeCallback sender, String url, String model, String voiceName) {
    this.callback = sender;

    Client.Builder b = Client.builder().apiKey(GeminiClient.GEMINI_API_KEY);
    ClientOptions clientOptions = ClientOptions.builder().build();
    b.clientOptions(clientOptions);

    this.client = b.build();

    if (model != null) {
      this.model = model;
    }
    if (voiceName != null) {
      this.voiceName = voiceName;
    }
  }

  public GoogleGeminiRealtimeBridge(RealtimeBridgeCallback sender) {
    this(sender, null, null, null);
  }

  @Override
  public CompletableFuture<Void> connect(RealtimeSetup realtimeSetup) {
    LiveConnectConfig config = buildLiveConfig();

    return client.async.live.connect(model, config).thenCompose(sess -> {
      this.session = sess;
      String sessionId = sess.sessionId();

      callback.session(sessionId);
      send(new WsVoiceAgentResponseMessage("gemini_connected", sessionId));

      try {
        sendPromptsIfAny(sess, realtimeSetup);
      } catch (Exception ex) {
        log.error("send setup prompts error(connect)", ex);
        send(new WsVoiceAgentResponseMessage("error", safe(ex.getMessage())));
      }

      CompletableFuture<Void> receiveFuture = sess.receive(this::onGeminiMessage);
      receiveFuture.whenComplete((v, ex) -> {
        log.info("gemini receive completed, v:{}, ex:{}", v, ex);
        if (ex != null) {
          log.error("gemini receive error", ex);
          send(new WsVoiceAgentResponseMessage("error", safe(ex.getMessage())));
        }
      });

      return receiveFuture;
    }).exceptionally(ex -> {
      log.error("Gemini live connect failed", ex);
      send(new WsVoiceAgentResponseMessage("error", safe(ex.getMessage())));
      callback.close("gemini connect failed");
      return null;
    });
  }

  @Override
  public CompletableFuture<Void> close() {
    try {
      AsyncSession s = this.session;
      if (s != null) {
        return s.close().exceptionally(ex -> null);
      }
    } finally {
      closeAssistantTurnSilently();
      try {
        client.close();
      } catch (Exception ignore) {
      }
      try {
        callback.close("close");
      } catch (Exception ignore) {
      }
    }
    return CompletableFuture.completedFuture(null);
  }

  /**
   * 前端推来的 16k PCM 裸流
   */
  @Override
  public CompletableFuture<Void> sendPcm16k(byte[] pcm16k) {
    AsyncSession s = this.session;
    if (s == null) {
      return CompletableFuture.completedFuture(null);
    }

    Blob audioBlob = Blob.builder().mimeType(INPUT_MIME).data(pcm16k).build();

    LiveSendRealtimeInputParameters params = LiveSendRealtimeInputParameters.builder().audio(audioBlob).build();

    return s.sendRealtimeInput(params).exceptionally(ex -> {
      String message = ex.getMessage();
      log.error("sendPcm16k error: {}", message, ex);
      send(new WsVoiceAgentResponseMessage("error", safe(message)));
      if ("org.java_websocket.exceptions.WebsocketNotConnectedException".equals(message)) {
        close();
      }
      return null;
    });
  }

  /**
   * 前端发文本输入
   */
  @Override
  public CompletableFuture<Void> sendText(String text) {
    AsyncSession s = this.session;
    if (s == null) {
      return CompletableFuture.completedFuture(null);
    }

    Content userMessage = Content.fromParts(Part.fromText(text));

    LiveSendClientContentParameters cc = LiveSendClientContentParameters.builder().turns(List.of(userMessage))
        .turnComplete(true).build();

    return s.sendClientContent(cc).exceptionally(ex -> {
      log.error("sendText error: {}", ex.getMessage(), ex);
      send(new WsVoiceAgentResponseMessage("error", safe(ex.getMessage())));
      return null;
    });
  }

  private void sendPromptsIfAny(AsyncSession s, RealtimeSetup realtimeSetup) {
    if (realtimeSetup == null) {
      return;
    }

    String systemPrompt = realtimeSetup.getSystem_prompt();
    String user_prompt = realtimeSetup.getUser_prompt();
    String greeting = realtimeSetup.getGreeting();
    List<SimpleChatMessage> messages = realtimeSetup.getMessages();

    List<Content> initialTurns = new ArrayList<>();

    if (StrUtil.notBlank(systemPrompt)) {
      initialTurns.add(Content.fromParts(Part.fromText(systemPrompt)));
    }
    if (StrUtil.notBlank(user_prompt)) {
      initialTurns.add(Content.fromParts(Part.fromText(user_prompt)));
    }

    for (SimpleChatMessage simpleChatMessage : messages) {
      String message = simpleChatMessage.getMessage();
      if (StrUtil.notBlank(message)) {
        initialTurns.add(Content.fromParts(Part.fromText(user_prompt)));
      }
    }

    if (StrUtil.notBlank(greeting)) {
      initialTurns.add(Content.fromParts(Part.fromText(greeting)));
    }

    if (!initialTurns.isEmpty()) {
      LiveSendClientContentParameters cc = LiveSendClientContentParameters.builder().turns(initialTurns)
          .turnComplete(true).build();

      s.sendClientContent(cc).exceptionally(ex -> {
        log.error("sendPromptsIfAny error: {}", ex.getMessage(), ex);
        send(new WsVoiceAgentResponseMessage("error", safe(ex.getMessage())));
        return null;
      });

      send(new WsVoiceAgentResponseMessage("setup_sent_to_model"));
    }
  }

  private LiveConnectConfig buildLiveConfig() {
    AutomaticActivityDetection vad = AutomaticActivityDetection.builder().disabled(false)
        .startOfSpeechSensitivity(StartSensitivity.Known.START_SENSITIVITY_HIGH)
        //
        .endOfSpeechSensitivity(EndSensitivity.Known.END_SENSITIVITY_HIGH)
        //
        .prefixPaddingMs(20).silenceDurationMs(150)
        //
        .build();

    RealtimeInputConfig realtimeInput = RealtimeInputConfig.builder().automaticActivityDetection(vad)
        .activityHandling(ActivityHandling.Known.START_OF_ACTIVITY_INTERRUPTS)
        .turnCoverage(TurnCoverage.Known.TURN_INCLUDES_ONLY_ACTIVITY).build();

    PrebuiltVoiceConfig prebuiltVoiceConfig = PrebuiltVoiceConfig.builder().voiceName(voiceName).build();

    VoiceConfig voiceConfig = VoiceConfig.builder().prebuiltVoiceConfig(prebuiltVoiceConfig).build();

    SpeechConfig speech = SpeechConfig.builder().voiceConfig(voiceConfig).build();

    ThinkingConfig thinkingConfig = ThinkingConfig.builder().thinkingBudget(0).build();

    AudioTranscriptionConfig audioTranscriptionConfig = AudioTranscriptionConfig.builder().build();

    return LiveConnectConfig.builder().responseModalities(List.of(new Modality(Modality.Known.AUDIO)))
        .speechConfig(speech).thinkingConfig(thinkingConfig).realtimeInputConfig(realtimeInput)
        .inputAudioTranscription(audioTranscriptionConfig).outputAudioTranscription(audioTranscriptionConfig).build();
  }

  /**
   * Gemini -> 前端
   */
  private void onGeminiMessage(LiveServerMessage msg) {
    try {
      if (msg == null) {
        return;
      }

      msg.serverContent().ifPresent(this::handleServerContent);

      msg.usageMetadata().ifPresent(usage -> {
        WsVoiceAgentResponseMessage m = new WsVoiceAgentResponseMessage("usage");
        m.setPromptTokenCount(usage.promptTokenCount());
        m.setResponseTokenCount(usage.responseTokenCount());
        m.setTotalTokenCount(usage.totalTokenCount());
        send(m);
      });

      msg.goAway().ifPresent(goAway -> {
        WsVoiceAgentResponseMessage m = new WsVoiceAgentResponseMessage("go_away");
        Optional<Duration> timeLeft = goAway.timeLeft();
        m.setTimeLeft(timeLeft.orElse(null));
        send(m);
      });

      msg.toolCall().ifPresent(toolCall -> {
        WsVoiceAgentResponseMessage m = new WsVoiceAgentResponseMessage("tool_call");
        m.setText(toolCall.toString());
        send(m);
      });

      msg.toolCallCancellation().ifPresent(cancel -> {
        WsVoiceAgentResponseMessage m = new WsVoiceAgentResponseMessage("tool_call_cancellation");
        m.setText(cancel.toString());
        send(m);
      });

    } catch (Exception e) {
      log.error("onGeminiMessage error", e);
      send(new WsVoiceAgentResponseMessage("error", safe(e.getMessage())));
    }
  }

  private void handleServerContent(LiveServerContent sc) {
    if (sc == null) {
      return;
    }

    sc.inputTranscription().ifPresent(t -> {
      String text = t.text().orElse("");
      if (StrUtil.isNotBlank(text)) {
        appendUserTranscript(text);

        WsVoiceAgentResponseMessage m = new WsVoiceAgentResponseMessage("transcript_in");
        m.setText(text);
        send(m);
      }
    });

    sc.outputTranscription().ifPresent(t -> {
      String text = t.text().orElse("");
      if (StrUtil.isNotBlank(text)) {
        ensureAssistantTurnStarted();

        appendAssistantTranscript(text);

        WsVoiceAgentResponseMessage m = new WsVoiceAgentResponseMessage("transcript_out");
        m.setText(text);
        m.setTurnId(currentAssistantTurnId);
        send(m);
      }
    });

    sc.modelTurn().ifPresent(modelTurn -> {
      List<Part> parts = modelTurn.parts().orElse(List.of());
      for (Part p : parts) {
        if (p == null) {
          continue;
        }

        p.text().ifPresent(text -> {
          if (StrUtil.isNotBlank(text)) {
            ensureAssistantTurnStarted();

            appendAssistantTranscript(text);

            WsVoiceAgentResponseMessage m = new WsVoiceAgentResponseMessage("text");
            m.setText(text);
            m.setTurnId(currentAssistantTurnId);
            send(m);
          }
        });

        p.inlineData().ifPresent(blob -> {
          String mt = blob.mimeType().orElse("");
          byte[] data = blob.data().orElse(null);

          if (data != null && mt.startsWith(OUTPUT_MIME_PREFIX)) {
            ensureAssistantTurnStarted();
            callback.sendBinary(data);
          }
        });
      }
    });

    sc.interrupted().ifPresent(v -> {
      if (Boolean.TRUE.equals(v)) {
        String turnId = currentAssistantTurnId;
        log.info("interrupted:{}", turnId);
        if (turnId != null) {
          WsVoiceAgentResponseMessage turnInterrupt = new WsVoiceAgentResponseMessage("assistant_turn_interrupt");
          turnInterrupt.setTurnId(turnId);
          send(turnInterrupt);
        }

        send(new WsVoiceAgentResponseMessage("interrupted"));
        closeAssistantTurnSilently();
      }
    });

    if (sc.turnComplete().orElse(false)) {
      String turnId = currentAssistantTurnId;
      if (turnId != null) {
        WsVoiceAgentResponseMessage turnComplete = new WsVoiceAgentResponseMessage("assistant_turn_complete");
        turnComplete.setTurnId(turnId);
        send(turnComplete);
      }

      send(new WsVoiceAgentResponseMessage("turn_complete"));
      flushTurnTranscriptOnComplete();
      closeAssistantTurnSilently();
    }
  }

  private String ensureAssistantTurnStarted() {
    synchronized (assistantTurnLock) {
      if (assistantTurnOpen && currentAssistantTurnId != null) {
        return currentAssistantTurnId;
      }

      currentAssistantTurnId = newAssistantTurnId();
      assistantTurnOpen = true;

      WsVoiceAgentResponseMessage m = new WsVoiceAgentResponseMessage("assistant_turn_start");
      m.setTurnId(currentAssistantTurnId);
      send(m);

      return currentAssistantTurnId;
    }
  }

  private void closeAssistantTurnSilently() {
    synchronized (assistantTurnLock) {
      assistantTurnOpen = false;
      currentAssistantTurnId = null;
    }
  }

  private String newAssistantTurnId() {
    return "asst_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().replace("-", "");
  }

  private void appendUserTranscript(String text) {
    synchronized (transcriptLock) {
      if (turnUserTranscript.length() > 0) {
        turnUserTranscript.append(' ');
      }
      turnUserTranscript.append(text);
    }
  }

  private void appendAssistantTranscript(String text) {
    synchronized (transcriptLock) {
      if (turnAssistantTranscript.length() > 0) {
        turnAssistantTranscript.append(' ');
      }
      turnAssistantTranscript.append(text);
    }
  }

  private void flushTurnTranscriptOnComplete() {
    synchronized (transcriptLock) {
      String userText = turnUserTranscript.toString().trim();
      String assistantText = turnAssistantTranscript.toString().trim();

      if (StrUtil.isNotBlank(userText) || StrUtil.isNotBlank(assistantText)) {
        WsVoiceAgentResponseMessage m = new WsVoiceAgentResponseMessage("turn_transcript");
        m.setInputText(userText);
        m.setOutputText(assistantText);
        send(m);
      }

      turnUserTranscript.setLength(0);
      turnAssistantTranscript.setLength(0);
    }
  }

  private void send(WsVoiceAgentResponseMessage msg) {
    try {
      String json = JsonUtils.toJson(msg);
      callback.sendText(json);
    } catch (Exception e) {
      log.error("send ws message error: {}", msg, e);
    }
  }

  private String safe(String s) {
    if (s == null) {
      return "";
    }
    return s.length() > 1000 ? s.substring(0, 1000) : s;
  }

  /**
   * 前端说“音频结束”
   */
  @Override
  public CompletableFuture<Void> endAudioInput() {
    AsyncSession s = this.session;
    if (s == null) {
      return CompletableFuture.completedFuture(null);
    }

    LiveSendRealtimeInputParameters params = LiveSendRealtimeInputParameters.builder().audioStreamEnd(true).build();

    return s.sendRealtimeInput(params).exceptionally(ex -> {
      String message = ex.getMessage();
      log.error("sendAudioStreamEnd error: {}", message, ex);
      send(new WsVoiceAgentResponseMessage("error", safe(message)));
      if ("org.java_websocket.exceptions.WebsocketNotConnectedException".equals(message)) {
        close();
      }
      return null;
    });
  }
}