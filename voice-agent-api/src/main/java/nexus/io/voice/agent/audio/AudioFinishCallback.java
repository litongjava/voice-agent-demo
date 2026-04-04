package nexus.io.voice.agent.audio;

import java.nio.file.Path;

public interface AudioFinishCallback {
  void done(Path audioFile);
}
