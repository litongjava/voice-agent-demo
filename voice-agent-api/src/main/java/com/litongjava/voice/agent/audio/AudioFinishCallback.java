package com.litongjava.voice.agent.audio;

import java.nio.file.Path;

public interface AudioFinishCallback {
  void done(Path audioFile);
}
