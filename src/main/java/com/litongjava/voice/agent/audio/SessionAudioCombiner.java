package com.litongjava.voice.agent.audio;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Pure Java utility:
 * - read PCM/WAV (16-bit little-endian, mono) into short[]
 * - resample short[] by linear interpolation
 * - interleave two mono streams into stereo short[]
 * - write stereo WAV with 16-bit little-endian samples
 *
 * Usage example:
 *   // assume userRawPcm: /tmp/session_user.pcm (16k, s16le mono)
 *   //       modelRawPcm: /tmp/session_model.pcm (24000, s16le mono)
 *   Combiner.combineToStereoWav("session123",
 *       Path.of("/tmp/session_user.pcm"),
 *       16000,
 *       Path.of("/tmp/session_model.pcm"),
 *       24000,
 *       Path.of("/tmp/session-session123-combined-16k-stereo.wav"),
 *       16000);
 */
public class SessionAudioCombiner {

  // ---------- High level combine function ----------
  public static Path combineToStereoWav(String sessionId, Path userPcmOrWav, int userSampleRate, Path modelPcmOrWav,
      int modelSampleRate, Path outWav, int targetSampleRate) throws IOException {
    short[] user = readPcm16Mono(userPcmOrWav);
    short[] model = readPcm16Mono(modelPcmOrWav);

    // resample to targetSampleRate if needed
    if (userSampleRate != targetSampleRate) {
      user = resampleLinear(user, userSampleRate, targetSampleRate);
    }
    if (modelSampleRate != targetSampleRate) {
      model = resampleLinear(model, modelSampleRate, targetSampleRate);
    }

    // make stereo interleaved: left=user, right=model
    short[] stereo = interleaveToStereo(user, model);

    // write wav (16-bit LE, stereo)
    writeWav16BitLE(outWav.toFile(), stereo, targetSampleRate, 2);

    return outWav;
  }

  // ---------- Read utilities ----------
  // Supports raw pcm (s16le mono) or wav (reads data chunk only)
  public static short[] readPcm16Mono(Path path) throws IOException {
    byte[] all = Files.readAllBytes(path);
    if (looksLikeWav(all)) {
      return readWav16MonoFromBytes(all);
    } else {
      return bytesToShortsLE(all);
    }
  }

  private static boolean looksLikeWav(byte[] data) {
    if (data.length < 12)
      return false;
    return data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F' && data[8] == 'W' && data[9] == 'A'
        && data[10] == 'V' && data[11] == 'E';
  }

  private static short[] readWav16MonoFromBytes(byte[] wav) throws IOException {
    // Simple wav parser: find "data" chunk and assume 16-bit PCM mono or stereo
    int idx = 12;
    int channels = 1;
    int bitsPerSample = 16;
    @SuppressWarnings("unused")
    int sampleRate = 16000;
    while (idx + 8 <= wav.length) {
      String chunkId = new String(wav, idx, 4, "ASCII");
      int chunkSize = littleEndianToInt(wav, idx + 4);
      int next = idx + 8 + chunkSize;
      if ("fmt ".equals(chunkId)) {
        // parse fmt chunk
        @SuppressWarnings("unused")
        int audioFormat = littleEndianToShort(wav, idx + 8);
        channels = littleEndianToShort(wav, idx + 10);
        sampleRate = littleEndianToInt(wav, idx + 12);
        bitsPerSample = littleEndianToShort(wav, idx + 22);
        // ignore rest
      } else if ("data".equals(chunkId)) {
        if (bitsPerSample != 16) {
          throw new IOException("Only 16-bit WAV supported in this reader. bitsPerSample=" + bitsPerSample);
        }
        int dataStart = idx + 8;
        int bytes = chunkSize;
        if (channels == 1) {
          byte[] pcm = new byte[bytes];
          System.arraycopy(wav, dataStart, pcm, 0, bytes);
          return bytesToShortsLE(pcm);
        } else if (channels == 2) {
          // convert stereo to mono by taking left channel (or average)
          byte[] pcm = new byte[bytes];
          System.arraycopy(wav, dataStart, pcm, 0, bytes);
          short[] stereo = bytesToShortsLE(pcm);
          short[] mono = new short[stereo.length / 2];
          for (int i = 0, j = 0; i < mono.length; i++, j += 2) {
            // average left & right to mono to be safe
            int l = stereo[j];
            int r = stereo[j + 1];
            mono[i] = (short) ((l + r) / 2);
          }
          return mono;
        } else {
          throw new IOException("Unsupported channel count: " + channels);
        }
      }
      idx = next;
    }
    throw new IOException("No data chunk found in WAV");
  }

  // ---------- Conversion helpers ----------
  private static short[] bytesToShortsLE(byte[] buf) {
    int n = buf.length / 2;
    short[] s = new short[n];
    for (int i = 0; i < n; i++) {
      int low = buf[2 * i] & 0xff;
      int high = buf[2 * i + 1]; // signed
      s[i] = (short) ((high << 8) | low);
    }
    return s;
  }

  private static byte[] shortsToBytesLE(short[] s) {
    byte[] b = new byte[s.length * 2];
    for (int i = 0; i < s.length; i++) {
      int v = s[i];
      b[2 * i] = (byte) (v & 0xff);
      b[2 * i + 1] = (byte) ((v >>> 8) & 0xff);
    }
    return b;
  }

  // ---------- Resampling by linear interpolation ----------
  // inRate -> outRate
  public static short[] resampleLinear(short[] in, int inRate, int outRate) {
    if (inRate == outRate)
      return in;
    double ratio = (double) outRate / (double) inRate;
    int outLen = (int) Math.floor(in.length * ratio);
    short[] out = new short[outLen];
    for (int i = 0; i < outLen; i++) {
      double pos = i / ratio;
      int iPos = (int) Math.floor(pos);
      double frac = pos - iPos;
      if (iPos + 1 < in.length) {
        double s0 = in[iPos];
        double s1 = in[iPos + 1];
        double sample = s0 + (s1 - s0) * frac;
        out[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, (int) Math.round(sample)));
      } else if (iPos < in.length) {
        out[i] = in[iPos];
      } else {
        out[i] = 0;
      }
    }
    return out;
  }

  // ---------- Interleave to stereo ----------
  // left and right mono arrays -> interleaved stereo short[]
  public static short[] interleaveToStereo(short[] left, short[] right) {
    int max = Math.max(left.length, right.length);
    short[] stereo = new short[max * 2];
    for (int i = 0; i < max; i++) {
      short l = i < left.length ? left[i] : 0;
      short r = i < right.length ? right[i] : 0;
      stereo[2 * i] = l;
      stereo[2 * i + 1] = r;
    }
    return stereo;
  }

  // ---------- WAV writing ----------
  // samples: interleaved samples (for stereo: L R L R ...)
  public static void writeWav16BitLE(File outFile, short[] samples, int sampleRate, int channels) throws IOException {
    try (FileOutputStream fos = new FileOutputStream(outFile);
        BufferedOutputStream bos = new BufferedOutputStream(fos)) {
      long dataChunkSize = samples.length * 2L;
      long riffChunkSize = 36 + dataChunkSize;

      // RIFF
      bos.write("RIFF".getBytes("ASCII"));
      bos.write(intToLE((int) riffChunkSize));
      bos.write("WAVE".getBytes("ASCII"));

      // fmt chunk
      bos.write("fmt ".getBytes("ASCII"));
      bos.write(intToLE(16)); // subchunk1 size
      bos.write(shortToLE((short) 1)); // PCM
      bos.write(shortToLE((short) channels));
      bos.write(intToLE(sampleRate));
      int byteRate = sampleRate * channels * 2;
      bos.write(intToLE(byteRate));
      short blockAlign = (short) (channels * 2);
      bos.write(shortToLE(blockAlign));
      bos.write(shortToLE((short) 16)); // bits per sample

      // data chunk
      bos.write("data".getBytes("ASCII"));
      bos.write(intToLE((int) dataChunkSize));

      // write samples
      byte[] data = shortsToBytesLE(samples);
      bos.write(data);
      bos.flush();
    }
  }

  // ---------- Little-endian helpers ----------
  private static byte[] intToLE(int v) {
    return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array();
  }

  private static byte[] shortToLE(short v) {
    return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v).array();
  }

  private static int littleEndianToInt(byte[] b, int offset) {
    return (b[offset] & 0xff) | ((b[offset + 1] & 0xff) << 8) | ((b[offset + 2] & 0xff) << 16)
        | ((b[offset + 3] & 0xff) << 24);
  }

  private static int littleEndianToShort(byte[] b, int offset) {
    return (b[offset] & 0xff) | ((b[offset + 1]) << 8);
  }
}