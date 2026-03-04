package com.litongjava.voice.agent.utils;

import com.litongjava.tio.core.ChannelContext;

public class ChannelContextUtils {

  public static String key(ChannelContext ctx) {
    // 用 tio 自己的唯一标识
    return ctx.getId();
  }

}
