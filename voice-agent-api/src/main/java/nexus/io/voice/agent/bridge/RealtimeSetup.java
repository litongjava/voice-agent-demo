package nexus.io.voice.agent.bridge;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class RealtimeSetup {
  private String system_prompt;
  private String user_prompt;
  private String greeting;
  private String language;
  private String session_id;

  private List<SimpleChatMessage> messages;

  public RealtimeSetup(String system_prompt, String user_prompt) {
    this.system_prompt = system_prompt;
    this.user_prompt = user_prompt;
  }

  public RealtimeSetup(String system_prompt, String user_prompt, String greeting) {
    this.system_prompt = system_prompt;
    this.user_prompt = user_prompt;
    this.language = greeting;

  }

  public RealtimeSetup(String system_prompt, String user_prompt, String greeting, String language) {
    this.system_prompt = system_prompt;
    this.user_prompt = user_prompt;
    this.greeting = greeting;
    this.language = language;

  }
}
