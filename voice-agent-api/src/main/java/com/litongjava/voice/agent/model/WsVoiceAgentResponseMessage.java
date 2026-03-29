package com.litongjava.voice.agent.model;

import java.time.Duration;
import java.util.Optional;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 后端 -> 前端 统一消息体
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WsVoiceAgentResponseMessage {

  private String type;
  // setup / session
  private String sessionId;
  // 通用字段
  private String text;
  private String turnId;
  private String audioBase64;
  private String inputText;
  private String outputText;
  // usage
  private Integer promptTokenCount;
  private Integer responseTokenCount;
  private Integer totalTokenCount;
  // goAway
  private Duration timeLeft;
  private String message;
  private String where;
  // function call
  private String name;

  public WsVoiceAgentResponseMessage(String type) {
    this.type = type;
  }

  public WsVoiceAgentResponseMessage(String type, String text) {
    this.type = type;
    this.text = text;
  }

  public WsVoiceAgentResponseMessage(String type, Optional<String> optional) {
    this.type = type;
    this.text = optional.orElse(null);
  }

  public void setPromptTokenCount(Optional<Integer> promptTokenCount) {
    this.promptTokenCount = promptTokenCount != null ? promptTokenCount.orElse(null) : null;
  }

  public void setResponseTokenCount(Optional<Integer> responseTokenCount) {
    this.responseTokenCount = responseTokenCount != null ? responseTokenCount.orElse(null) : null;
  }

  public void setTotalTokenCount(Optional<Integer> totalTokenCount) {
    this.totalTokenCount = totalTokenCount != null ? totalTokenCount.orElse(null) : null;
  }
}