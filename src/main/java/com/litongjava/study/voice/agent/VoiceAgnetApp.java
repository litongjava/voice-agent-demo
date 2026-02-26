package com.litongjava.study.voice.agent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import com.litongjava.study.voice.agent.config.VaAdminConfig;
import com.litongjava.tio.boot.TioApplication;
import com.litongjava.tio.boot.server.TioBootServer;

public class VoiceAgnetApp {
  public static void main(String[] args) {
    long start = System.currentTimeMillis();

    // 1. 虚拟线程工厂（用于 work 线程）
    ThreadFactory workTf = Thread.ofVirtual().name("t-io-v-", 1).factory();

    // 2. 设置 readWorkers 使用的线程工厂
    TioBootServer server = TioBootServer.me();
    server.setWorkThreadFactory(workTf);
    server.setWorkThreadNum(Runtime.getRuntime().availableProcessors() * 8);

    // 3. 创建业务虚拟线程 Executor（每任务一个虚拟线程）
    ThreadFactory bizTf = Thread.ofVirtual().name("t-biz-v-", 0).factory();
    ExecutorService bizExecutor = Executors.newThreadPerTaskExecutor(bizTf);
    server.setBizExecutor(bizExecutor);

    VaAdminConfig vaAdminConfig = new VaAdminConfig();
    TioApplication.run(VoiceAgnetApp.class, vaAdminConfig, args);
    long end = System.currentTimeMillis();
    System.out.println((end - start) + "ms");
  }
}
