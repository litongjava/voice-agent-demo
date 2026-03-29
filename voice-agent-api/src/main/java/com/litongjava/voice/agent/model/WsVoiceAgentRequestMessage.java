package com.litongjava.voice.agent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * WebSocket 前端发来的消息结构
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WsVoiceAgentRequestMessage {
  private Long meeting_id;
  private String platform;
  private String type; // 消息类型：setup | text | audio_end | close | ...
  private String text; // 当 type == "text" 时的文本
  private String system_prompt; // 当 type == "setup" 时的系统提示
  private String user_prompt; // 当 type == "setup" 时的用户提示
  private String greeting;// 当 type == "setup" 时的欢迎语
  private String language;
}