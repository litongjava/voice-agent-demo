package com.litongjava.voice.agent.bridge;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class SimpleChatMessage {
  private String name, role, message;

  public static SimpleChatMessage buildUser(String name, String message) {
    return new SimpleChatMessage(name, "user", message);

  }
}
