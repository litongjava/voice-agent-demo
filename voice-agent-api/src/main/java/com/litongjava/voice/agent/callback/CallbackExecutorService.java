package com.litongjava.voice.agent.callback;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class CallbackExecutorService {

  public static final ScheduledExecutorService SHARED_SCHEDULER =
      Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "ws-realtime-bridge-callback-scheduler");
        t.setDaemon(true);
        return t;
      });
}
