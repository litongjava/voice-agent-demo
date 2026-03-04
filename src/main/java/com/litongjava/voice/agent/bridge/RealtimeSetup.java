package com.litongjava.voice.agent.bridge;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain=true)
public class RealtimeSetup {
  private String system_prompt; // 当 type == "setup" 时的系统提示
  private String user_prompt; // 当 type == "setup" 时的用户提示
  private String job_description;
  private String resume;
  private String questions;
  private String greeting;
}
