package com.litongjava.voice.agent.sip;

import com.litongjava.template.PromptEngine;
import com.litongjava.voice.agent.bridge.RealtimeSetup;

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

    return new RealtimeSetup(systemPrompt, userPrompt, jobDescription, resume, questions, greeting);
  }
}