package com.litongjava.voice.agent.audio;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public class SessionAudioExecutor {
  private static final ThreadFactory FACTORY = Thread.ofVirtual().name("session-audio-combine-", 0).factory();
  public static final ExecutorService COMBINE_EXECUTOR = Executors.newThreadPerTaskExecutor(FACTORY);
}