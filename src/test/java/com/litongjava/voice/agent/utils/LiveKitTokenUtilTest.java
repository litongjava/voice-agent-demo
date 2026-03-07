package com.litongjava.voice.agent.utils;

import org.junit.Test;

public class LiveKitTokenUtilTest {

  @Test
  public void test() {
    String token = LiveKitTokenUtil.buildSipAdminCallToken("admin", 3600);
    System.out.println(token);
  }
}
