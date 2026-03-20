package com.litongjava.voice.agent.callback;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.litongjava.media.NativeMedia;
import com.litongjava.tio.core.ChannelContext;
import com.litongjava.tio.core.Tio;
import com.litongjava.tio.utils.json.JsonUtils;
import com.litongjava.tio.websocket.common.WebSocketResponse;
import com.litongjava.voice.agent.audio.AudioFinishCallback;
import com.litongjava.voice.agent.audio.SessionAudioRecorder;
import com.litongjava.voice.agent.bridge.RealtimeBridgeCallback;
import com.litongjava.voice.agent.bridge.RealtimeSetup;
import com.litongjava.voice.agent.consts.VoiceAgentConst;
import com.litongjava.voice.agent.model.WsVoiceAgentResponseMessage;
import com.litongjava.voice.agent.utils.ChannelContextUtils;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class WsRealtimeBridgeCallback implements RealtimeBridgeCallback {

  private final ChannelContext channelContext;
  private final String sessionId;

  /**
   * 是否开启主动介入
   */
  private volatile boolean proactiveInterventionEnabled = false;

  /**
   * assistant 完成回复后，用户沉默多久开始主动介入
   */
  private volatile long proactiveInterventionTimeoutMs = 8_000L;

  /**
   * 两次主动介入之间的最小间隔
   */
  private volatile long proactiveInterventionRepeatMs = 8_000L;

  /**
   * 当前是否处于“assistant 已说完，等待用户回答”的阶段
   */
  private volatile boolean waitingForUserAnswer = false;

  /**
   * 最近一次 assistant 完成一轮回复的时间
   */
  private volatile long lastAssistantTurnCompleteAt = 0L;

  /**
   * 最近一次真实检测到用户说话/输入文本的时间
   */
  private volatile long lastRealUserSpeechAt = 0L;

  /**
   * 最近一次 assistant 活动时间
   */
  private volatile long lastAssistantActivityAt = 0L;

  /**
   * 最近一次任意活动时间
   */
  private volatile long lastActivityAt = System.currentTimeMillis();

  /**
   * 最近一次主动介入时间
   */
  private volatile long lastProactiveInterventionAt = 0L;

  /**
   * 最近一次 assistant 文本
   */
  private volatile String lastAssistantText = "";

  /**
   * 最近一次用户文本
   */
  private volatile String lastUserText = "";

  /**
   * 是否已经关闭
   */
  private volatile boolean closed = false;

  /**
   * 由 handler 注入，真正把文本发送给模型
   */
  private volatile Consumer<String> modelTextSender;

  private final AtomicBoolean proactiveTaskStarted = new AtomicBoolean(false);

  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
    @Override
    public Thread newThread(Runnable r) {
      Thread t = new Thread(r, "ws-realtime-bridge-callback-" + sessionId);
      t.setDaemon(true);
      return t;
    }
  });

  public WsRealtimeBridgeCallback(ChannelContext channelContext) {
    this.channelContext = channelContext;
    this.sessionId = ChannelContextUtils.key(channelContext);
  }

  public void bindModelTextSender(Consumer<String> modelTextSender) {
    this.modelTextSender = modelTextSender;
  }

  public void configureProactiveIntervention(boolean enabled, long timeoutMs, long repeatMs) {
    this.proactiveInterventionEnabled = enabled;
    if (timeoutMs > 0) {
      this.proactiveInterventionTimeoutMs = timeoutMs;
    }
    if (repeatMs > 0) {
      this.proactiveInterventionRepeatMs = repeatMs;
    }
  }

  /**
   * 仅表示有音频流在上传，不代表用户真的开口。
   * 所以这里不改变 waitingForUserAnswer，不参与“沉默结束”判断。
   */
  public void onUserAudioActivity() {
    this.lastActivityAt = System.currentTimeMillis();
  }

  /**
   * 用户明确发送文本输入，视为真实回答。
   */
  public void onUserTextActivity(String text) {
    this.lastUserText = safeText(text);
    markRealUserSpeechActivity("user_text_input");
  }

  @Override
  public void sendText(String json) {
    inspectServerEvent(json);

    WebSocketResponse wsResp = WebSocketResponse.fromText(json, VoiceAgentConst.CHARSET);
    Tio.send(channelContext, wsResp);
  }

  @Override
  public void sendBinary(byte[] bytes) {
    try {
      SessionAudioRecorder.appendModelPcm(sessionId, bytes);
    } catch (Exception ex) {
      log.warn("record model pcm failed: {}", ex.getMessage());
    }

    markAssistantActivity();

    WebSocketResponse wsResp = WebSocketResponse.fromBytes(bytes);
    Tio.send(channelContext, wsResp);
  }

  @Override
  public void close(String reason) {
    closed = true;

    try {
      scheduler.shutdownNow();
    } catch (Exception e) {
      log.warn("shutdown scheduler failed: {}", e.getMessage());
    }

    AudioFinishCallback audioFinishCallback = new AudioFinishCallback() {
      @Override
      public void done(Path audioFile) {
        String wavFilePath = audioFile.toString();
        NativeMedia.toMp3(wavFilePath);
      }
    };

    SessionAudioRecorder.stop(sessionId, audioFinishCallback);
    Tio.remove(channelContext, reason);
  }

  @Override
  public void session(String sessionId) {
  }

  /**
   * 如果 bridge 显式调用了 turnComplete，这里直接用。
   */
  @Override
  public void turnComplete(String role, String text) {
    if (closed) {
      return;
    }

    if ("assistant".equalsIgnoreCase(role) || "model".equalsIgnoreCase(role)) {
      this.lastAssistantText = safeText(text);
      enterWaitingForUserAnswer("turnComplete(role=assistant)");
    } else if ("user".equalsIgnoreCase(role)) {
      this.lastUserText = safeText(text);
      markRealUserSpeechActivity("turnComplete(role=user)");
    }
  }

  @Override
  public void start(RealtimeSetup setup) {
    startProactiveTaskIfNeeded();
  }

  private void startProactiveTaskIfNeeded() {
    if (!proactiveTaskStarted.compareAndSet(false, true)) {
      return;
    }

    scheduler.scheduleAtFixedRate(() -> {
      try {
        checkAndTriggerProactiveIntervention();
      } catch (Throwable e) {
        log.warn("checkAndTriggerProactiveIntervention error, sessionId:{}", sessionId, e);
      }
    }, 1, 1, TimeUnit.SECONDS);
  }

  private void checkAndTriggerProactiveIntervention() {
    if (closed) {
      return;
    }

    if (!proactiveInterventionEnabled) {
      return;
    }

    if (!waitingForUserAnswer) {
      return;
    }

    Consumer<String> sender = this.modelTextSender;
    if (sender == null) {
      return;
    }

    if (lastAssistantTurnCompleteAt <= 0L) {
      return;
    }

    long now = System.currentTimeMillis();
    long idleMs = now - lastAssistantTurnCompleteAt;

    if (idleMs < proactiveInterventionTimeoutMs) {
      return;
    }

    long sinceLastIntervention = now - lastProactiveInterventionAt;
    if (lastProactiveInterventionAt > 0L && sinceLastIntervention < proactiveInterventionRepeatMs) {
      return;
    }

    String interventionPrompt = CallbackPromptUtils.buildProactiveInterventionPrompt(lastAssistantText, lastUserText, idleMs);

    log.info(
        "trigger proactive intervention, sessionId:{}, idleMs:{}, waitingForUserAnswer:{}, lastAssistantTurnCompleteAt:{}",
        sessionId, idleMs, waitingForUserAnswer, lastAssistantTurnCompleteAt);

    lastProactiveInterventionAt = now;

    try {
      sender.accept(interventionPrompt);
      markAssistantActivity();
    } catch (Exception e) {
      log.warn("modelTextSender.accept failed, sessionId:{}, prompt:{}", sessionId, interventionPrompt, e);
    }
  }

  private void inspectServerEvent(String json) {
    if (json == null || json.isEmpty()) {
      return;
    }

    try {
      WsVoiceAgentResponseMessage msg = JsonUtils.parse(json, WsVoiceAgentResponseMessage.class);
      if (msg == null || msg.getType() == null) {
        return;
      }

      String type = msg.getType();

      if ("transcript_in".equalsIgnoreCase(type)) {
        this.lastUserText = safeText(msg.getText());
        markRealUserSpeechActivity("transcript_in");
        return;
      }

      if ("speech_started".equalsIgnoreCase(type)) {
        markRealUserSpeechActivity("speech_started");
        return;
      }

      if ("transcript_out".equalsIgnoreCase(type) || "text".equalsIgnoreCase(type)) {
        this.lastAssistantText = safeText(msg.getText());
        markAssistantActivity();
        return;
      }

      if ("assistant_turn_start".equalsIgnoreCase(type)) {
        markAssistantActivity();
        return;
      }

      if ("assistant_turn_complete".equalsIgnoreCase(type) || "turn_complete".equalsIgnoreCase(type)) {
        enterWaitingForUserAnswer(type);
        return;
      }

      if ("assistant_turn_interrupt".equalsIgnoreCase(type) || "interrupted".equalsIgnoreCase(type)) {
        markRealUserSpeechActivity(type);
        return;
      }

      if ("error".equalsIgnoreCase(type) || "go_away".equalsIgnoreCase(type)) {
        markAssistantActivity();
      }
    } catch (Exception e) {
      log.debug("inspectServerEvent parse failed, sessionId:{}, json:{}", sessionId, json);
    }
  }

  private void enterWaitingForUserAnswer(String reason) {
    long now = System.currentTimeMillis();
    this.waitingForUserAnswer = true;
    this.lastAssistantTurnCompleteAt = now;
    this.lastActivityAt = now;

    log.info("enter waitingForUserAnswer, sessionId:{}, reason:{}, proactiveEnabled:{}, lastAssistantText:{}",
        sessionId, reason, proactiveInterventionEnabled, shortText(lastAssistantText));
  }

  private void markRealUserSpeechActivity(String reason) {
    long now = System.currentTimeMillis();
    this.lastRealUserSpeechAt = now;
    this.lastActivityAt = now;
    this.waitingForUserAnswer = false;

    log.info("mark real user speech activity, sessionId:{}, reason:{}, lastUserText:{}", sessionId, reason,
        shortText(lastUserText));
  }

  private void markAssistantActivity() {
    long now = System.currentTimeMillis();
    this.lastAssistantActivityAt = now;
    this.lastActivityAt = now;
  }

  private String safeText(String text) {
    return text == null ? "" : text.trim();
  }

  private String shortText(String text) {
    if (text == null) {
      return "";
    }
    String s = text.trim();
    return s.length() <= 120 ? s : s.substring(0, 120) + "...";
  }
}