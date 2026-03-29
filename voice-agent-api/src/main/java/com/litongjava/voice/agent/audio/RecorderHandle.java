package com.litongjava.voice.agent.audio;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * RecorderHandle with time-aligned writes:
 * - sessionStartMillis: reference time for all pads
 * - userSamplesWritten / modelSamplesWritten: counts samples written so far (samples, not bytes)
 * - writeUser/writeModel are synchronized and will insert zero-bytes (silence) if the arrival time
 *   implies there should be earlier samples that haven't been written.
 *
 * Assumes 16-bit PCM, mono (2 bytes / sample).
 */
public class RecorderHandle {
  public final String sessionId;
  public final int userSampleRate;
  public final int modelSampleRate;
  public final Path userRaw;
  public final Path modelRaw;
  public final Path userWav;
  public final Path modelWav;
  public final BufferedOutputStream userOut;
  public final BufferedOutputStream modelOut;

  // session absolute start time (ms)
  private final long sessionStartMillis;

  // samples written so far for each channel (in samples, not bytes)
  private long userSamplesWritten = 0L;
  private long modelSamplesWritten = 0L;

  // helper: bytes per sample for 16-bit PCM mono
  private static final int BYTES_PER_SAMPLE = 2;

  public RecorderHandle(String sessionId, int userSampleRate, int modelSampleRate) throws IOException {
    this.sessionId = sessionId;
    this.userSampleRate = userSampleRate;
    this.modelSampleRate = modelSampleRate;
    this.userRaw = Paths.get(SessionAudioConst.TEMP_RECORDINGS_OUT_DIR, "session-" + sessionId + "_user.pcm");
    this.modelRaw = Paths.get(SessionAudioConst.TEMP_RECORDINGS_OUT_DIR, "session-" + sessionId + "_model.pcm");
    this.userWav = Paths.get(SessionAudioConst.TEMP_RECORDINGS_OUT_DIR, "session-" + sessionId + "_user.wav");
    this.modelWav = Paths.get(SessionAudioConst.TEMP_RECORDINGS_OUT_DIR, "session-" + sessionId + "_model.wav");

    // ensure directory exists
    Files.createDirectories(Paths.get(SessionAudioConst.TEMP_RECORDINGS_OUT_DIR));

    // start fresh: overwrite existing files for new session
    this.userOut = new BufferedOutputStream(new FileOutputStream(userRaw.toFile(), false));
    this.modelOut = new BufferedOutputStream(new FileOutputStream(modelRaw.toFile(), false));

    this.sessionStartMillis = System.currentTimeMillis();
    this.userSamplesWritten = 0L;
    this.modelSamplesWritten = 0L;
  }

  /**
   * Write user PCM (16-bit mono). Synchronized to avoid concurrent pad/write races.
   * pcm - raw bytes (must be multiple of 2)
   */
  public synchronized void writeUser(byte[] pcm) throws IOException {
    if (pcm == null || pcm.length == 0) {
      return;
    }
    // calculate expected samples according to wall-clock
    long now = System.currentTimeMillis();
    long expectedSamples = ((now - sessionStartMillis) * userSampleRate) / 1000L;
    long toPadSamples = expectedSamples - userSamplesWritten;
    if (toPadSamples > 0) {
      writeZeros(userOut, toPadSamples * BYTES_PER_SAMPLE);
      userSamplesWritten += toPadSamples;
    }
    // write actual pcm
    userOut.write(pcm);
    // update sample counter
    userSamplesWritten += pcm.length / BYTES_PER_SAMPLE;
  }

  /**
   * Write model PCM (16-bit mono). Synchronized.
   */
  public synchronized void writeModel(byte[] pcm) throws IOException {
    if (pcm == null || pcm.length == 0) {
      return;
    }
    long now = System.currentTimeMillis();
    long expectedSamples = ((now - sessionStartMillis) * modelSampleRate) / 1000L;
    long toPadSamples = expectedSamples - modelSamplesWritten;
    if (toPadSamples > 0) {
      writeZeros(modelOut, toPadSamples * BYTES_PER_SAMPLE);
      modelSamplesWritten += toPadSamples;
    }
    modelOut.write(pcm);
    modelSamplesWritten += pcm.length / BYTES_PER_SAMPLE;
  }

  /**
   * Ensure stream flushed and closed, then produce wav files (with INFO) and kick off combine.
   */
  public Path finishAndClose(AudioFinishCallback callback) {
    // 1) 关闭流，写出 wav（带 INFO）
    try {
      synchronized (this) {
        // flush any buffered data
        try {
          userOut.flush();
        } catch (IOException ignored) {
        }
        try {
          modelOut.flush();
        } catch (IOException ignored) {
        }
      }
      userOut.close();
    } catch (IOException ignored) {
    }
    try {
      modelOut.close();
    } catch (IOException ignored) {
    }

    try {
      AudioWavUtils.writeWavFromPcmWithInfo(userRaw.toFile(), userWav.toFile(), userSampleRate, 16, 1, sessionId,
          "user");
    } catch (Exception e) {
      // 这里尽量不要抛出，记录并继续尝试处理另一个文件
      e.printStackTrace();
    }
    try {
      AudioWavUtils.writeWavFromPcmWithInfo(modelRaw.toFile(), modelWav.toFile(), modelSampleRate, 16, 1, sessionId,
          "model");
    } catch (Exception e) {
      e.printStackTrace();
    }

    // 2) 异步合并为 stereo，并把合成文件命名里包含 sessionId
    // 输出合成文件路径：session-<id>-combined-<target>hz-stereo.wav
    int targetRate = 16000; // 按需调整
    Path outCombined = Paths.get(SessionAudioConst.RECORDINGS_OUT_DIR,
        "session-" + sessionId + "-combined-" + targetRate + "hz-stereo.wav");

    SessionAudioExecutor.COMBINE_EXECUTOR.submit(() -> {
      try {
        // 传入 userWav 和 modelWav（Combiner/SessionAudioCombiner 会识别 wav 并读取采样率）
        SessionAudioCombiner.combineToStereoWav(sessionId, userWav, userSampleRate, modelWav, modelSampleRate,
            outCombined, targetRate);
        if (callback != null) {
          callback.done(outCombined);
        }

        // 合成完成后（可选）删除中间 wav 文件
        // Files.deleteIfExists(userWav);
        // Files.deleteIfExists(modelWav);
      } catch (Throwable t) {
        t.printStackTrace();
      } finally {
        // 3) 删除原始 raw pcm
        try {
          Files.deleteIfExists(userRaw);
        } catch (IOException ignored) {
        }
        try {
          Files.deleteIfExists(modelRaw);
        } catch (IOException ignored) {
        }
      }
    });
    return outCombined;
  }

  public void closeQuietly() {
    try {
      userOut.close();
    } catch (Exception ignore) {
    }
    try {
      modelOut.close();
    } catch (Exception ignore) {
    }
  }

  // ---------- helper: write zero bytes ----------
  private void writeZeros(BufferedOutputStream out, long bytesToWrite) throws IOException {
    final int chunk = 4096;
    byte[] zeros = new byte[chunk];
    while (bytesToWrite > 0) {
      int w = (int) Math.min(chunk, bytesToWrite);
      out.write(zeros, 0, w);
      bytesToWrite -= w;
    }
  }
}