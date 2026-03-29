package com.litongjava.voice.agent.bridge.elevenlabs;

import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.litongjava.elevenlabs.client.ElevenLabsAgentsClient;
import com.litongjava.elevenlabs.client.ElevenLabsClient;
import com.litongjava.elevenlabs.listener.ElevenLabsAgentsListener;
import com.litongjava.elevenlabs.model.ElevenLabsAgentsConfig;
import com.litongjava.tio.utils.json.JsonUtils;
import com.litongjava.voice.agent.bridge.RealtimeBridgeCallback;
import com.litongjava.voice.agent.bridge.RealtimeModelBridge;
import com.litongjava.voice.agent.bridge.RealtimeSetup;
import com.litongjava.voice.agent.model.WsVoiceAgentResponseMessage;
import com.litongjava.voice.agent.utils.RealtimeSetupUtils;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ElevenLabsRealtimeModelBridge implements RealtimeModelBridge {

  private final RealtimeBridgeCallback callback;
  private final ElevenLabsAgentsClient client;

  private volatile boolean closed = false;
  private volatile String activeTurnId;
  private volatile String lastAgentText = "";

  public ElevenLabsRealtimeModelBridge(RealtimeBridgeCallback callback) {
    this.callback = callback;
    this.client = new ElevenLabsAgentsClient(new ElevenLabsAgentsListener() {

      @Override
      public void onSessionCreated(String conversationId) {
        callback.session(conversationId);

        WsVoiceAgentResponseMessage out = new WsVoiceAgentResponseMessage("setup_complete");
        out.setSessionId(conversationId);
        callback.sendText(JsonUtils.toSkipNullJson(out));
      }

      @Override
      public void onUserTranscript(String text) {
        callback.sendText(JsonUtils.toSkipNullJson(new WsVoiceAgentResponseMessage("transcript_in", text)));
      }

      @Override
      public void onAgentText(String text) {
        lastAgentText = text == null ? "" : text;

        ensureAssistantTurnStarted();
        callback.sendText(JsonUtils.toSkipNullJson(new WsVoiceAgentResponseMessage("transcript_out", text)));
      }

      @Override
      public void onAgentAudio(String audioBase64) {
        ensureAssistantTurnStarted();
        callback.sendAudio(activeTurnId, audioBase64);
      }

      @Override
      public void onInterrupted() {
        WsVoiceAgentResponseMessage out = new WsVoiceAgentResponseMessage("assistant_turn_interrupt");
        out.setTurnId(activeTurnId);
        callback.sendText(JsonUtils.toSkipNullJson(out));
        activeTurnId = null;
      }

      @Override
      public void onError(Throwable e) {
        callback.sendText(JsonUtils.toSkipNullJson(new WsVoiceAgentResponseMessage("error", e.getMessage())));
        callback.close("elevenlabs failure");
      }

      @Override
      public void onClosed(int code, String reason) {
        callback.close("elevenlabs closed: " + reason);
      }
    });
  }

  @Override
  public CompletableFuture<Void> connect(RealtimeSetup setup) {
    String language = setup.getLanguage();
    String systemMessage = RealtimeSetupUtils.buildSystemMessage(setup);
    String firstMessage = RealtimeSetupUtils.buildFirstMessage(setup);

    try {
      String signedUrl = ElevenLabsClient.getSignedUrl();
      ElevenLabsAgentsConfig config = ElevenLabsAgentsConfig.builder().signedUrl(signedUrl)
          //
          .systemPrompt(systemMessage).firstMessage(firstMessage).language(language)
          //
          .build();

      CompletableFuture<Void> future = client.connect(config);
      String json = JsonUtils.toSkipNullJson(new WsVoiceAgentResponseMessage("setup_sent_to_model", "ok"));
      callback.sendText(json);
      return future;
    } catch (Exception e) {
      CompletableFuture<Void> future = new CompletableFuture<>();
      future.completeExceptionally(e);
      return future;
    }
  }

  @Override
  public CompletableFuture<Void> sendPcm16k(byte[] pcm16k) {
    if (closed) {
      return CompletableFuture.completedFuture(null);
    }
    if (pcm16k == null || pcm16k.length == 0) {
      return CompletableFuture.completedFuture(null);
    }
    String base64 = Base64.getEncoder().encodeToString(pcm16k);

    return client.sendUserAudioChunk(base64);
  }

  @Override
  public CompletableFuture<Void> endAudioInput() {
    if (closed) {
      return CompletableFuture.completedFuture(null);
    }

    client.endUserInput();

    if (activeTurnId != null) {
      WsVoiceAgentResponseMessage complete = new WsVoiceAgentResponseMessage("assistant_turn_complete");
      complete.setTurnId(activeTurnId);
      callback.sendText(JsonUtils.toSkipNullJson(complete));
      callback.turnComplete("assistant", lastAgentText);
      activeTurnId = null;
    }

    return CompletableFuture.completedFuture(null);
  }

  @Override
  public CompletableFuture<Void> sendText(String text) {
    if (closed) {
      return CompletableFuture.completedFuture(null);
    }
    return client.sendText(text);
  }

  @Override
  public CompletableFuture<Void> close() {
    closed = true;
    return client.close();
  }

  private void ensureAssistantTurnStarted() {
    if (activeTurnId == null) {
      activeTurnId = UUID.randomUUID().toString();
      WsVoiceAgentResponseMessage start = new WsVoiceAgentResponseMessage("assistant_turn_start");
      start.setTurnId(activeTurnId);
      callback.sendText(JsonUtils.toSkipNullJson(start));
    }
  }
}