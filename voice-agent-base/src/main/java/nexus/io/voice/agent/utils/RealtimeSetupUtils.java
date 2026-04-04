package nexus.io.voice.agent.utils;

import java.util.List;

import nexus.io.voice.agent.bridge.RealtimeSetup;
import nexus.io.voice.agent.bridge.SimpleChatMessage;

public class RealtimeSetupUtils {

  public static String buildSystemMessage(RealtimeSetup setup) {
    String system_prompt = setup.getSystem_prompt();
    String user_prompt = setup.getUser_prompt();
    StringBuilder sb = new StringBuilder();

    List<SimpleChatMessage> messages = setup.getMessages();

    appendSection(sb, "SYSTEM_PROMPT", system_prompt);
    appendSection(sb, "USER_PROMPT", user_prompt);
    for (SimpleChatMessage message : messages) {
      appendSection(sb, message.getName(), message.getMessage());
    }

    String result = sb.toString().trim();
    if (result.isEmpty()) {
      return null;
    }
    return result;
  }

  public static String buildFirstMessage(RealtimeSetup setup) {
    String greeting = setup.getGreeting();
    String s = greeting == null ? "" : greeting.trim();
    if (!s.isEmpty()) {
      return s;
    }
    return null;
  }

  private static void appendSection(StringBuilder sb, String title, String content) {
    if (content == null || content.trim().isEmpty()) {
      return;
    }
    if (sb.length() > 0) {
      sb.append("\n\n");
    }
    sb.append("[").append(title).append("]\n").append(content.trim());
  }
}
