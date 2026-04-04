package nexus.io.voice.agent.audio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class SessionAudioRecorder {

  private static final ConcurrentMap<String, RecorderHandle> handles = new ConcurrentHashMap<>();

  static {
    try {
      Files.createDirectories(Paths.get(SessionAudioConst.TEMP_RECORDINGS_OUT_DIR));
      Files.createDirectories(Paths.get(SessionAudioConst.RECORDINGS_OUT_DIR));
    } catch (IOException e) {
      // ignore
    }
  }

  public static void start(String sessionId, int userSampleRate, int modelSampleRate) throws IOException {
    RecorderHandle h = new RecorderHandle(sessionId, userSampleRate, modelSampleRate);
    RecorderHandle old = handles.put(sessionId, h);
    if (old != null) {
      old.closeQuietly();
    }
  }

  public static Path stop(String sessionId, AudioFinishCallback callback) {
    RecorderHandle h = handles.remove(sessionId);
    if (h != null) {
      return h.finishAndClose(callback);
    }
    return null;
  }

  public static void appendUserPcm(String sessionId, byte[] pcm16k) throws IOException {
    RecorderHandle h = handles.get(sessionId);
    if (h != null) {
      h.writeUser(pcm16k);
    }
  }

  public static void appendModelPcm(String sessionId, byte[] pcm) throws IOException {
    RecorderHandle h = handles.get(sessionId);
    if (h != null) {
      h.writeModel(pcm);
    }
  }

  public static Path getUserWavPath(String sessionId) {
    RecorderHandle h = handles.get(sessionId);
    if (h != null)
      return h.userWav;
    return Paths.get(SessionAudioConst.TEMP_RECORDINGS_OUT_DIR, "session-" + sessionId + "_user.wav");
  }

  public static Path getModelWavPath(String sessionId) {
    RecorderHandle h = handles.get(sessionId);
    if (h != null)
      return h.modelWav;
    return Paths.get(SessionAudioConst.TEMP_RECORDINGS_OUT_DIR, "session-" + sessionId + "_model.wav");
  }

}