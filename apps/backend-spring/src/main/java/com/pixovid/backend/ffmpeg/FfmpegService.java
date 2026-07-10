package com.pixovid.backend.ffmpeg;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * Port of apps/backend/src/lib/ffmpeg.ts: shells out to ffmpeg/ffprobe (must be on PATH) to
 * stitch/composite the template timeline into one mp4, mix audio clips, extract thumbnails, and
 * probe uploaded media duration/dimensions.
 */
@Service
public class FfmpegService {

  public record ImageSize(int width, int height) {}

  /**
   * Compose an ordered list of timeline segments into a single mp4. Each segment is a trimmed
   * slice of one clip (or a black gap), normalised to a common size, concatenated in order. Audio
   * parts are mixed together (summed where they overlap) and laid over the video, padded/trimmed
   * to match the video length.
   */
  public byte[] stitchTimeline(
      List<byte[]> clips, List<TimelineSegment> segments, List<AudioPart> audioParts, int width, int height, int fps) {
    if (segments.isEmpty()) {
      throw new FfmpegException("Cannot stitch zero segments.", null);
    }
    Path dir = createTempDir("tpl-stitch-");
    try {
      List<Path> clipPaths = new ArrayList<>();
      for (int i = 0; i < clips.size(); i++) {
        Path p = dir.resolve("clip-" + i + ".mp4");
        Files.write(p, clips.get(i));
        clipPaths.add(p);
      }
      List<Path> audioPaths = new ArrayList<>();
      for (int i = 0; i < audioParts.size(); i++) {
        Path p = dir.resolve("audio-" + i + ".bin");
        Files.write(p, audioParts.get(i).data());
        audioPaths.add(p);
      }

      String scale =
          "scale=%d:%d:force_original_aspect_ratio=decrease,pad=%d:%d:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=%d,format=yuv420p"
              .formatted(width, height, width, height, fps);

      // One decode input per clip-backed segment (lets a clip recur in several slices). Each
      // input is seeked at the demuxer level (-ss/-t before -i) so ffmpeg jumps to the slice's
      // in-point instead of decoding the clip from 0 every time.
      List<String> inputArgs = new ArrayList<>();
      List<String> parts = new ArrayList<>();
      List<String> labels = new ArrayList<>();
      int inputIdx = 0;
      for (int k = 0; k < segments.size(); k++) {
        TimelineSegment seg = segments.get(k);
        String label = "seg" + k;
        labels.add("[" + label + "]");
        if (seg.clip() == null) {
          parts.add(
              "color=c=black:s=%dx%d:r=%d:d=%s,format=yuv420p,setsar=1[%s]"
                  .formatted(width, height, fps, fmt(seg.length()), label));
        } else {
          inputArgs.add("-ss");
          inputArgs.add(fmt(seg.inPoint()));
          inputArgs.add("-t");
          inputArgs.add(fmt(seg.length()));
          inputArgs.add("-i");
          inputArgs.add(clipPaths.get(seg.clip()).toString());
          parts.add("[%d:v]setpts=PTS-STARTPTS,%s[%s]".formatted(inputIdx, scale, label));
          inputIdx++;
        }
      }
      parts.add("%sconcat=n=%d:v=1:a=0[outv]".formatted(String.join("", labels), segments.size()));

      List<String> mapArgs = new ArrayList<>(List.of("-map", "[outv]"));

      if (!audioParts.isEmpty()) {
        List<String> audioLabels = new ArrayList<>();
        for (int i = 0; i < audioParts.size(); i++) {
          AudioPart part = audioParts.get(i);
          inputArgs.add("-i");
          inputArgs.add(audioPaths.get(i).toString());
          long delayMs = Math.max(0, Math.round(part.startSec() * 1000));
          String label = "a" + i;
          audioLabels.add("[" + label + "]");
          parts.add(
              "[%d:a]atrim=start=%s:duration=%s,asetpts=PTS-STARTPTS,aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo,adelay=%d|%d[%s]"
                  .formatted(inputIdx, fmt(part.inPoint()), fmt(part.length()), delayMs, delayMs, label));
          inputIdx++;
        }
        if (audioParts.size() == 1) {
          parts.add(audioLabels.get(0) + "apad[outa]");
        } else {
          parts.add(
              "%samix=inputs=%d:normalize=0:dropout_transition=0[amixed]"
                  .formatted(String.join("", audioLabels), audioParts.size()));
          parts.add("[amixed]apad[outa]");
        }
        mapArgs.addAll(List.of("-map", "[outa]", "-shortest", "-c:a", "aac", "-b:a", "192k"));
      }

      Path outPath = dir.resolve("out.mp4");
      List<String> args = new ArrayList<>(inputArgs);
      args.add("-filter_complex");
      args.add(String.join(";", parts));
      args.addAll(mapArgs);
      args.addAll(List.of("-c:v", "libx264", "-pix_fmt", "yuv420p", "-movflags", "+faststart", outPath.toString()));
      runFfmpeg(args);

      return Files.readAllBytes(outPath);
    } catch (IOException e) {
      throw new FfmpegException("Failed to stitch timeline", e);
    } finally {
      deleteRecursively(dir);
    }
  }

  /**
   * Build a standalone audio clip (MP3) for a block's time window by trimming each overlapping
   * audio part to its slice, delaying it to its position within the window, and mixing them. The
   * result is exactly {@code totalSec} long (padded with silence).
   */
  public byte[] mixAudioWindow(List<AudioWindowPart> parts, double totalSec) {
    if (parts.isEmpty()) {
      throw new FfmpegException("No audio parts to mix.", null);
    }
    Path dir = createTempDir("tpl-lipaudio-");
    try {
      List<String> inputArgs = new ArrayList<>();
      List<String> filters = new ArrayList<>();
      List<String> labels = new ArrayList<>();
      for (int i = 0; i < parts.size(); i++) {
        AudioWindowPart p = parts.get(i);
        Path path = dir.resolve("a-" + i + ".bin");
        Files.write(path, p.data());
        inputArgs.add("-i");
        inputArgs.add(path.toString());
        long delayMs = Math.max(0, Math.round(p.delaySec() * 1000));
        String label = "a" + i;
        labels.add("[" + label + "]");
        filters.add(
            "[%d:a]atrim=start=%s:duration=%s,asetpts=PTS-STARTPTS,aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo,adelay=%d|%d[%s]"
                .formatted(i, fmt(p.inPoint()), fmt(p.length()), delayMs, delayMs, label));
      }
      if (parts.size() == 1) {
        filters.add(labels.get(0) + "apad,atrim=0:" + fmt(totalSec) + "[outa]");
      } else {
        filters.add(
            "%samix=inputs=%d:normalize=0:dropout_transition=0,apad,atrim=0:%s[outa]"
                .formatted(String.join("", labels), parts.size(), fmt(totalSec)));
      }
      Path outPath = dir.resolve("out.mp3");
      List<String> args = new ArrayList<>(inputArgs);
      args.add("-filter_complex");
      args.add(String.join(";", filters));
      args.addAll(List.of("-map", "[outa]", "-c:a", "libmp3lame", "-b:a", "192k", outPath.toString()));
      runFfmpeg(args);
      return Files.readAllBytes(outPath);
    } catch (IOException e) {
      throw new FfmpegException("Failed to mix audio window", e);
    } finally {
      deleteRecursively(dir);
    }
  }

  /** Read an uploaded media file's duration (seconds) via ffprobe. Throws if unreadable. */
  public double probeMediaDuration(byte[] media) {
    Path dir = createTempDir("tpl-probe-");
    try {
      Path inPath = dir.resolve("in.bin");
      Files.write(inPath, media);
      String out =
          runFfprobe(
              List.of(
                  "-v", "error", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1",
                  inPath.toString()));
      double seconds;
      try {
        seconds = Double.parseDouble(out.trim());
      } catch (NumberFormatException e) {
        seconds = Double.NaN;
      }
      if (!Double.isFinite(seconds) || seconds <= 0) {
        throw new FfmpegException("Could not determine the uploaded file's duration.", null);
      }
      return seconds;
    } catch (IOException e) {
      throw new FfmpegException("Failed to probe media duration", e);
    } finally {
      deleteRecursively(dir);
    }
  }

  /** Read an image's pixel dimensions via ffprobe. Throws if unreadable. */
  public ImageSize probeImageSize(byte[] image) {
    Path dir = createTempDir("tpl-imgsize-");
    try {
      Path inPath = dir.resolve("in.bin");
      Files.write(inPath, image);
      String out =
          runFfprobe(
              List.of(
                  "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height", "-of",
                  "csv=s=x:p=0", inPath.toString()));
      String[] wh = out.trim().split("x");
      if (wh.length != 2) {
        throw new FfmpegException("Could not read image dimensions.", null);
      }
      int w = Integer.parseInt(wh[0]);
      int h = Integer.parseInt(wh[1]);
      if (w <= 0 || h <= 0) {
        throw new FfmpegException("Could not read image dimensions.", null);
      }
      return new ImageSize(w, h);
    } catch (IOException | NumberFormatException e) {
      throw new FfmpegException("Failed to probe image size", e);
    } finally {
      deleteRecursively(dir);
    }
  }

  /** Extract a single JPEG thumbnail frame from a video buffer. */
  public byte[] generateThumbnail(byte[] video, double atSeconds) {
    Path dir = createTempDir("tpl-thumb-");
    try {
      Path inPath = dir.resolve("in.mp4");
      Path outPath = dir.resolve("thumb.jpg");
      Files.write(inPath, video);
      try {
        runFfmpeg(List.of("-ss", fmt(atSeconds), "-i", inPath.toString(), "-frames:v", "1", "-q:v", "3", outPath.toString()));
      } catch (FfmpegException e) {
        // Video may be shorter than atSeconds; fall back to the very first frame.
        runFfmpeg(List.of("-i", inPath.toString(), "-frames:v", "1", "-q:v", "3", outPath.toString()));
      }
      return Files.readAllBytes(outPath);
    } catch (IOException e) {
      throw new FfmpegException("Failed to generate thumbnail", e);
    } finally {
      deleteRecursively(dir);
    }
  }

  // ---------------------------------------------------------------------------
  // Process execution helpers
  // ---------------------------------------------------------------------------

  private void runFfmpeg(List<String> args) {
    List<String> command = new ArrayList<>(List.of("ffmpeg", "-y"));
    command.addAll(args);
    runProcess(command, "ffmpeg", "required to stitch template videos");
  }

  private String runFfprobe(List<String> args) {
    List<String> command = new ArrayList<>(List.of("ffprobe"));
    command.addAll(args);
    return runProcess(command, "ffprobe", "required to read uploaded media duration/dimensions");
  }

  private String runProcess(List<String> command, String executable, String requiredFor) {
    Process process;
    try {
      process = new ProcessBuilder(command).redirectErrorStream(false).start();
    } catch (IOException e) {
      throw new FfmpegException(executable + " is not installed or not on PATH (" + requiredFor + ").", e);
    }
    try {
      String stdout = readAll(process.getInputStream());
      String stderr = readAll(process.getErrorStream());
      int code = process.waitFor();
      if (code != 0) {
        String tail = stderr.length() > 2000 ? stderr.substring(stderr.length() - 2000) : stderr;
        throw new FfmpegException(executable + " exited with code " + code + ": " + tail, null);
      }
      return stdout;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new FfmpegException("Interrupted while running " + executable, e);
    } catch (IOException e) {
      throw new FfmpegException("Failed to read " + executable + " output", e);
    }
  }

  private static String readAll(InputStream stream) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    stream.transferTo(buffer);
    return buffer.toString(StandardCharsets.UTF_8);
  }

  private static Path createTempDir(String prefix) {
    try {
      return Files.createTempDirectory(prefix);
    } catch (IOException e) {
      throw new FfmpegException("Failed to create temp directory", e);
    }
  }

  private static void deleteRecursively(Path dir) {
    if (dir == null) {
      return;
    }
    try (var walk = Files.walk(dir)) {
      walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> p.toFile().delete());
    } catch (IOException ignored) {
      // Best-effort cleanup of a temp dir; nothing meaningful to do if it fails.
    }
  }

  /** Formats a duration/timestamp value for an ffmpeg CLI arg (e.g. "1.5", "2"). */
  private static String fmt(double seconds) {
    return String.format(Locale.ROOT, "%s", seconds == Math.rint(seconds) ? String.valueOf((long) seconds) : String.valueOf(seconds));
  }

  public static class FfmpegException extends RuntimeException {
    public FfmpegException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
