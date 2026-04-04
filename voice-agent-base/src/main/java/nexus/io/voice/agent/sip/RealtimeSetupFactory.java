package nexus.io.voice.agent.sip;

import java.util.ArrayList;
import java.util.List;

import nexus.io.template.PromptEngine;
import nexus.io.voice.agent.bridge.RealtimeSetup;
import nexus.io.voice.agent.bridge.SimpleChatMessage;

public final class RealtimeSetupFactory {

  private RealtimeSetupFactory() {

  }

  public static RealtimeSetup buildFromEnv() {
    String systemPrompt = PromptEngine.renderToString("voice_agent_system_prompt.txt");
    String userPrompt = PromptEngine.renderToString("voice_agent_user_prompt.txt");
    String jobDescription = PromptEngine.renderToString("voice_agent_job_description.txt");
    String resume = PromptEngine.renderToString("voice_agent_resume.txt");
    String questions = PromptEngine.renderToString("voice_agent_questions.txt");
    String greeting = PromptEngine.renderToString("voice_agent_greeting.txt");

    List<SimpleChatMessage> messages = new ArrayList<>();
    messages.add(SimpleChatMessage.buildUser("JOB_DESCRIPTION", jobDescription));
    messages.add(SimpleChatMessage.buildUser("RESUME", resume));
    messages.add(SimpleChatMessage.buildUser("QUESTIONS", questions));
    
    RealtimeSetup realtimeSetup = new RealtimeSetup(systemPrompt, userPrompt,greeting);
    realtimeSetup.setMessages(messages);
    return realtimeSetup;
  }
}