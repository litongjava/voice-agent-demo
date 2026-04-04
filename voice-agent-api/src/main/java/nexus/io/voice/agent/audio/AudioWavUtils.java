package nexus.io.voice.agent.audio;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class AudioWavUtils {

  // ---- helper: write WAV header + data, and add LIST/INFO chunk with sessionId
  public static void writeWavFromPcmWithInfo(File pcmFile, File wavFile, int sampleRate, int samplesizeBits,
      int channels, String sessionId, String role) throws IOException {
    long pcmLength = pcmFile.length();
    long dataChunkSize = pcmLength;

    // build INFO chunk (LIST)
    // We'll put INAM = "session:<sessionId>;role:<role>"
    String inamValue = "session:" + sessionId + ";role:" + role;
    byte[] inamBytes = inamValue.getBytes("UTF-8");
    int inamSize = inamBytes.length;
    // INAM chunk must be even-sized; pad if needed
    boolean inamPad = (inamSize % 2) != 0;
    int inamChunkTotalSize = 4 + 4 + inamSize + (inamPad ? 1 : 0); // "INAM" id + size + data + pad
    // LIST chunk: "LIST" + size + "INFO" + INAM...
    int infoDataSize = 4 + inamChunkTotalSize; // "INFO" + INAM chunk
    boolean infoPad = (infoDataSize % 2) != 0;
    int listChunkTotalSize = infoDataSize + (infoPad ? 1 : 0);

    long riffChunkSize = 4 /* "WAVE" */ + (8 + 16) /* fmt */ + (8 + listChunkTotalSize) /* LIST */
        + (8 + dataChunkSize);

    try (FileOutputStream fos = new FileOutputStream(wavFile);
        BufferedOutputStream bos = new BufferedOutputStream(fos);
        FileInputStream fis = new FileInputStream(pcmFile);
        BufferedInputStream bis = new BufferedInputStream(fis)) {

      // RIFF header
      bos.write("RIFF".getBytes("ASCII"));
      bos.write(intToLittleEndian((int) riffChunkSize));
      bos.write("WAVE".getBytes("ASCII"));

      // fmt chunk
      bos.write("fmt ".getBytes("ASCII"));
      bos.write(intToLittleEndian(16)); // subchunk1 size
      bos.write(shortToLittleEndian((short) 1)); // AudioFormat = 1 PCM
      bos.write(shortToLittleEndian((short) channels));
      bos.write(intToLittleEndian(sampleRate));
      long byteRate = (long) sampleRate * channels * (samplesizeBits / 8);
      bos.write(intToLittleEndian((int) byteRate));
      bos.write(shortToLittleEndian((short) (channels * (samplesizeBits / 8))));
      bos.write(shortToLittleEndian((short) samplesizeBits));

      // LIST chunk (INFO)
      bos.write("LIST".getBytes("ASCII"));
      bos.write(intToLittleEndian(listChunkTotalSize));
      bos.write("INFO".getBytes("ASCII"));

      // INAM chunk
      bos.write("INAM".getBytes("ASCII"));
      bos.write(intToLittleEndian(inamSize));
      bos.write(inamBytes);
      if (inamPad) {
        bos.write(0);
      }

      // optionally pad LIST data to even size
      if (infoPad) {
        bos.write(0);
      }

      // data chunk
      bos.write("data".getBytes("ASCII"));
      bos.write(intToLittleEndian((int) dataChunkSize));

      // copy pcm data
      byte[] buffer = new byte[4096];
      int r;
      while ((r = bis.read(buffer)) != -1) {
        bos.write(buffer, 0, r);
      }
      bos.flush();
    }
  }
  

  private static byte[] intToLittleEndian(int v) {
    ByteBuffer bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
    bb.putInt(v);
    return bb.array();
  }

  private static byte[] shortToLittleEndian(short v) {
    ByteBuffer bb = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
    bb.putShort(v);
    return bb.array();
  }
}
