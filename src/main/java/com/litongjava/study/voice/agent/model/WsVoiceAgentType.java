package com.litongjava.study.voice.agent.model;


/**
 * WebSocket 消息类型枚举
 */
public enum WsVoiceAgentType {
  SETUP,
  //
  TEXT,
  //
  AUDIO_END,
  //
  CLOSE,

  // server -> client 响应类型（也可复用同一枚举）
  ERROR,
  //
  SETUP_RECEIVED,
  //
  SETUP_SENT_TO_MODEL,
  //
  GEMINI_CONNECTED,
  //
  SETUP_COMPLETE,
  //
  TRANSCRIPT_IN,
  //
  TRANSCRIPT_OUT,
  //
  TURN_COMPLETE,
  //
  GO_AWAY,
  //
  USAGE,
  //
  INLINE_DATA,
  //
  FUNCTION_CALL,
  //
  IGNORED
}