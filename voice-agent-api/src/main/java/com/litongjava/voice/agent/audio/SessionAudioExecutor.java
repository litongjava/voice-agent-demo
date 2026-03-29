package com.litongjava.voice.agent.audio;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class SessionAudioExecutor {
  private static final ThreadFactory FACTORY = new ThreadFactory() {
    private final AtomicInteger counter = new AtomicInteger(0);

    @Override
    public Thread newThread(Runnable r) {
      Thread t = new Thread(r);
      t.setName("session-audio-combine-" + counter.getAndIncrement());
      return t;
    }
  };

  public static final ExecutorService COMBINE_EXECUTOR = Executors.newCachedThreadPool(FACTORY);
}