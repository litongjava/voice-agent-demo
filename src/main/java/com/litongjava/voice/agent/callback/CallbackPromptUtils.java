package com.litongjava.voice.agent.callback;

public class CallbackPromptUtils {

  public static String buildProactiveInterventionPrompt(String lastAssistantText, String lastUserText, long idleMs) {
    long idleSec = Math.max(1L, idleMs / 1000L);

    String assistantContext = emptyToDefault(lastAssistantText, "无");
    String userContext = emptyToDefault(lastUserText, "无");

    return "" + "系统提示：当前是实时语音场景。\n"
    //
        + "模型刚刚已经完成了一轮提问或回应，直到现在用户已经沉默了 " + idleSec + " 秒，仍未开始正式回答。\n"
        //
        + "请你根据当前上下文主动介入，但要自然、简洁、像真人模型，不要机械重复。\n"
        //
        + "你的目标是推动对话继续进行。\n" + "你可以视上下文选择：\n"
        //
        + "1. 温和提醒用户继续回答；\n"
        //
        + "2. 如果用户可能卡住了，给一个轻微引导；\n"
        //
        + "3. 如果用户已回答过部分内容，可基于他的内容继续追问；\n"
        //
        + "4. 如果问题较难，也可以建议先给简短结论再展开。\n"
        //
        + "请直接输出你要对用户说的话，不要解释策略。\n"
        //
        + "最近一轮模型内容：" + assistantContext + "\n"
        //
        + "最近用户内容：" + userContext;
  }

  private static String emptyToDefault(String value, String dft) {
    return value == null || value.trim().isEmpty() ? dft : value.trim();
  }

}
