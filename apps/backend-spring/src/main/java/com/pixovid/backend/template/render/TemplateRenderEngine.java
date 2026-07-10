package com.pixovid.backend.template.render;

import com.pixovid.backend.config.AppProperties;
import com.pixovid.backend.facefusion.FaceFusionClient;
import com.pixovid.backend.ffmpeg.AudioPart;
import com.pixovid.backend.ffmpeg.AudioWindowPart;
import com.pixovid.backend.ffmpeg.FfmpegService;
import com.pixovid.backend.ffmpeg.TimelineSegment;
import com.pixovid.backend.openrouter.GenerateImageParams;
import com.pixovid.backend.openrouter.GenerateVideoParams;
import com.pixovid.backend.openrouter.GeneratedImage;
import com.pixovid.backend.openrouter.GeneratedVideo;
import com.pixovid.backend.openrouter.ImageRef;
import com.pixovid.backend.openrouter.OpenRouterClient;
import com.pixovid.backend.openrouter.SwapFaceParams;
import com.pixovid.backend.storage.StorageService;
import com.pixovid.backend.template.RenderBlockPhase;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.stereotype.Service;

/**
 * Port of apps/backend/src/lib/templateRender.ts: generates every block of a template (face-
 * swapping the avatar onto start/end frames when enabled), stitches the clips across tracks,
 * mixes timeline audio over them, and produces a thumbnail.
 */
@Service
public class TemplateRenderEngine {

  private final FaceFusionClient faceFusion;
  private final OpenRouterClient openRouter;
  private final FfmpegService ffmpeg;
  private final StorageService storage;
  private final AppProperties appProperties;

  public TemplateRenderEngine(
      FaceFusionClient faceFusion,
      OpenRouterClient openRouter,
      FfmpegService ffmpeg,
      StorageService storage,
      AppProperties appProperties) {
    this.faceFusion = faceFusion;
    this.openRouter = openRouter;
    this.ffmpeg = ffmpeg;
    this.storage = storage;
    this.appProperties = appProperties;
  }

  // ---------------------------------------------------------------------------
  // Small shared helpers
  // ---------------------------------------------------------------------------

  /** Sniff an image mime type from the leading bytes (defaults to png). */
  private static String sniffImageMime(byte[] data) {
    if (data.length >= 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
      return "image/jpeg";
    }
    if (data.length >= 12 && new String(data, 8, 4).equals("WEBP")) {
      return "image/webp";
    }
    return "image/png";
  }

  private static String ext(String mime) {
    return "image/jpeg".equals(mime) ? "jpg" : "image/webp".equals(mime) ? "webp" : "png";
  }

  private static String toDataUrl(byte[] data, String mime) {
    return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(data);
  }

  private record Dimensions(int width, int height) {}

  /** Map an aspect ratio to output dimensions for the stitched video. */
  private static Dimensions aspectToDims(String aspectRatio) {
    if (aspectRatio == null) {
      return new Dimensions(1280, 720);
    }
    return switch (aspectRatio) {
      case "9:16" -> new Dimensions(720, 1280);
      case "1:1" -> new Dimensions(1024, 1024);
      case "4:3" -> new Dimensions(960, 720);
      case "3:4" -> new Dimensions(720, 960);
      default -> new Dimensions(1280, 720);
    };
  }

  private record AspectRatioOption(String label, double value) {}

  private static final List<AspectRatioOption> ASPECT_RATIOS =
      List.of(
          new AspectRatioOption("21:9", 21.0 / 9),
          new AspectRatioOption("16:9", 16.0 / 9),
          new AspectRatioOption("3:2", 3.0 / 2),
          new AspectRatioOption("4:3", 4.0 / 3),
          new AspectRatioOption("5:4", 5.0 / 4),
          new AspectRatioOption("1:1", 1.0),
          new AspectRatioOption("4:5", 4.0 / 5),
          new AspectRatioOption("3:4", 3.0 / 4),
          new AspectRatioOption("2:3", 2.0 / 3),
          new AspectRatioOption("9:16", 9.0 / 16));

  /** Snap actual pixel dimensions to the closest supported aspect-ratio string. */
  private static String nearestAspectRatio(int width, int height) {
    double r = (double) width / height;
    AspectRatioOption best = ASPECT_RATIOS.get(0);
    for (AspectRatioOption a : ASPECT_RATIOS) {
      if (Math.abs(a.value() - r) < Math.abs(best.value() - r)) {
        best = a;
      }
    }
    return best.label();
  }

  private record SwapEngine(boolean flux, String model) {}

  /**
   * Resolve the effective swap engine for a block. swapModel may be "facefusion" (local
   * service), an OpenRouter image model id (diffusion edit), or null (server default).
   */
  private SwapEngine resolveSwapEngine(String swapModel) {
    if ("facefusion".equals(swapModel)) {
      return new SwapEngine(false, null);
    }
    if (swapModel != null && !swapModel.isBlank()) {
      return new SwapEngine(true, swapModel);
    }
    return "flux".equals(appProperties.getSwap().getProvider())
        ? new SwapEngine(true, appProperties.getOpenrouter().getSwapModel())
        : new SwapEngine(false, null);
  }

  public record FaceSwapOptions(String swapModel, String context, String aspectRatio) {}

  /**
   * Apply a face swap onto a single frame using the block's chosen engine: a diffusion identity
   * edit (honors context) via an OpenRouter model, or the classic FaceFusion pixel swap. For the
   * diffusion path the output aspect ratio is taken from the frame's actual dimensions.
   */
  public ImageBytes applyFaceSwap(byte[] face, byte[] frame, String frameMime, FaceSwapOptions opts) {
    String faceMime = sniffImageMime(face);
    SwapEngine engine = resolveSwapEngine(opts.swapModel());
    if (engine.flux()) {
      String aspect = opts.aspectRatio();
      try {
        FfmpegService.ImageSize size = ffmpeg.probeImageSize(frame);
        aspect = nearestAspectRatio(size.width(), size.height());
      } catch (Exception ignored) {
        // fall back to the provided aspect ratio (or none)
      }
      GeneratedImage result =
          openRouter.swapFaceWithImageModel(
              new SwapFaceParams(
                  engine.model(),
                  new SwapFaceParams.FaceImage(face, faceMime),
                  new SwapFaceParams.FaceImage(frame, frameMime),
                  opts.context(),
                  aspect));
      return new ImageBytes(result.data(), result.contentType());
    }
    FaceFusionClient.FaceSwapResult result =
        faceFusion.faceSwap(
            new FaceFusionClient.FaceSwapInput(face, faceMime, "face." + ext(faceMime)),
            new FaceFusionClient.FaceSwapInput(frame, frameMime, "frame." + ext(frameMime)));
    return new ImageBytes(result.data(), result.contentType());
  }

  /**
   * Run {@code fn}, retrying up to {@code attempts} times (total) on any error with a short
   * linear backoff. Throws the last error if every attempt fails.
   */
  private <T> T withRetry(Callable<T> fn, int attempts, String label, java.util.function.IntConsumer onRetry) {
    Exception lastErr = null;
    for (int attempt = 1; attempt <= attempts; attempt++) {
      try {
        return fn.call();
      } catch (Exception e) {
        lastErr = e;
        if (attempt < attempts) {
          if (onRetry != null) {
            onRetry.accept(attempt + 1);
          }
          try {
            Thread.sleep(2000L * attempt);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RenderException("Interrupted during retry backoff for " + label, ie);
          }
        }
      }
    }
    throw new RenderException(label + ": all " + attempts + " attempts failed — " + lastErr.getMessage(), lastErr);
  }

  // ---------------------------------------------------------------------------
  // Single-block rendering
  // ---------------------------------------------------------------------------

  public interface PhaseCallback {
    void onPhase(RenderBlockPhase phase, Integer attempt);
  }

  public interface SwappedFramesCallback {
    void onSwapped(ImageBytes start, ImageBytes end);
  }

  public record RenderBlockClipOptions(
      boolean useSwapCache,
      String lipsyncAudioUrl,
      PhaseCallback onPhase,
      ImageBytes cachedSwapStart,
      ImageBytes cachedSwapEnd,
      SwappedFramesCallback onSwapped) {

    public static RenderBlockClipOptions of(boolean useSwapCache) {
      return new RenderBlockClipOptions(useSwapCache, null, null, null, null, null);
    }
  }

  /**
   * Generate a single block's video clip: face-swap the avatar onto the start/end frames when
   * enabled, pass the avatar as a reference, then call the model. When {@code useSwapCache} is
   * set, an already-generated swap preview is reused instead of swapping again.
   */
  public ClipResult renderBlockClip(RenderBlockSpec block, byte[] face, RenderBlockClipOptions opts) {
    boolean swapStartFresh =
        block.faceSwapStart() && block.startImageKey() != null && face != null && opts.cachedSwapStart() == null;
    boolean swapEndFresh =
        block.faceSwapEnd() && block.endImageKey() != null && face != null && opts.cachedSwapEnd() == null;
    if ((swapStartFresh || swapEndFresh) && opts.onPhase() != null) {
      opts.onPhase().onPhase(RenderBlockPhase.FACE_SWAP, null);
    }

    ImageBytes startFrame =
        opts.cachedSwapStart() != null
            ? opts.cachedSwapStart()
            : prepareFrame(block.startImageKey(), block.faceSwapStart(), block.swappedStartKey(), face, block, opts.useSwapCache());
    ImageBytes endFrame =
        opts.cachedSwapEnd() != null
            ? opts.cachedSwapEnd()
            : prepareFrame(block.endImageKey(), block.faceSwapEnd(), block.swappedEndKey(), face, block, opts.useSwapCache());

    if (opts.onSwapped() != null) {
      opts.onSwapped().onSwapped(swapStartFresh ? startFrame : null, swapEndFresh ? endFrame : null);
    }

    List<ImageRef> references = new ArrayList<>();
    if (face != null) {
      references.add(new ImageRef(toDataUrl(face, sniffImageMime(face))));
    }

    int duration =
        block.duration() != null ? block.duration() : Math.max(1, (int) Math.round(block.endSec() - block.startSec()));

    GenerateVideoParams.AudioRef audioReference =
        block.lipsync() && opts.lipsyncAudioUrl() != null && OpenRouterClient.supportsAudioLipsync(block.model())
            ? new GenerateVideoParams.AudioRef(opts.lipsyncAudioUrl())
            : null;

    if (opts.onPhase() != null) {
      opts.onPhase().onPhase(RenderBlockPhase.VIDEO_GENERATION, null);
    }
    ImageBytes finalStartFrame = startFrame;
    ImageBytes finalEndFrame = endFrame;
    GeneratedVideo generated =
        withRetry(
            () ->
                openRouter.generateVideo(
                    new GenerateVideoParams(
                        block.model(),
                        block.prompt(),
                        duration,
                        block.resolution(),
                        block.aspectRatio(),
                        audioReference != null ? null : Boolean.FALSE,
                        finalStartFrame != null ? new ImageRef(toDataUrl(finalStartFrame.data(), finalStartFrame.mime())) : null,
                        finalEndFrame != null ? new ImageRef(toDataUrl(finalEndFrame.data(), finalEndFrame.mime())) : null,
                        references.isEmpty() ? null : references,
                        audioReference)),
            appProperties.getRender().getVideoMaxAttempts(),
            "Video generation (model " + block.model() + ")",
            nextAttempt -> {
              if (opts.onPhase() != null) {
                opts.onPhase().onPhase(RenderBlockPhase.RETRYING, nextAttempt);
              }
            });

    return new ClipResult(generated.data(), generated.contentType(), generated.cost() != null ? generated.cost() : 0);
  }

  private ImageBytes prepareFrame(
      String key, boolean swap, String swappedKey, byte[] face, RenderBlockSpec block, boolean useSwapCache) {
    if (key == null) {
      return null;
    }
    if (swap && useSwapCache && swappedKey != null) {
      byte[] cached = storage.downloadObject(swappedKey);
      return new ImageBytes(cached, sniffImageMime(cached));
    }
    byte[] data = storage.downloadObject(key);
    String mime = sniffImageMime(data);
    if (swap && face != null) {
      return applyFaceSwap(face, data, mime, new FaceSwapOptions(block.swapModel(), block.swapContext(), block.aspectRatio()));
    }
    return new ImageBytes(data, mime);
  }

  // ---------------------------------------------------------------------------
  // Lip-sync audio + timeline segments
  // ---------------------------------------------------------------------------

  private record BlockWindow(double startSec, double endSec) {}

  /**
   * Build the audio that plays under a block's time window (mixing all overlapping timeline
   * audio clips), upload it, and return its public URL — or null if no audio overlaps the block.
   */
  public String buildBlockLipsyncAudio(List<RenderAudioClipSpec> audioClips, double blockStartSec, double blockEndSec) {
    double windowLen = blockEndSec - blockStartSec;
    if (windowLen <= 0 || audioClips.isEmpty()) {
      return null;
    }

    List<AudioWindowPart> parts = new ArrayList<>();
    for (RenderAudioClipSpec clip : audioClips) {
      double dur = clip.duration() != null ? clip.duration() : 0;
      double cropStart = clip.cropStart() != null ? clip.cropStart() : 0;
      double cropEnd = clip.cropEnd() != null ? clip.cropEnd() : (dur > 0 ? dur : 0);
      double footprint = Math.max(0, cropEnd - cropStart);
      if (footprint <= 0) {
        continue;
      }
      double clipEnd = clip.startSec() + footprint;
      double overlapStart = Math.max(blockStartSec, clip.startSec());
      double overlapEnd = Math.min(blockEndSec, clipEnd);
      double length = overlapEnd - overlapStart;
      if (length <= 0.05) {
        continue;
      }
      double inPoint = cropStart + (overlapStart - clip.startSec());
      double delaySec = overlapStart - blockStartSec;
      byte[] data = storage.downloadObject(clip.audioKey());
      parts.add(new AudioWindowPart(data, inPoint, length, delaySec));
    }
    if (parts.isEmpty()) {
      return null;
    }
    byte[] mixed = ffmpeg.mixAudioWindow(parts, windowLen);
    String key = storage.uploadBuffer(mixed, "audio/mpeg", "templates/lipsync", "mp3");
    return storage.getPublicUrl(key);
  }

  /**
   * Resolve overlapping, multi-track blocks into a flat list of timeline segments. For each
   * timeline slice the visible block is the one on the highest track that fully spans it.
   */
  public List<TimelineSegment> buildTimelineSegments(List<RenderBlockSpec> blocks) {
    double total = round(blocks.stream().mapToDouble(RenderBlockSpec::endSec).max().orElse(0));
    if (total <= 0) {
      return List.of();
    }

    Set<Double> bounds = new LinkedHashSet<>();
    bounds.add(0.0);
    bounds.add(total);
    for (RenderBlockSpec b : blocks) {
      if (b.startSec() > 0 && b.startSec() < total) {
        bounds.add(round(b.startSec()));
      }
      if (b.endSec() > 0 && b.endSec() < total) {
        bounds.add(round(b.endSec()));
      }
    }
    List<Double> points = new ArrayList<>(bounds);
    points.sort(Double::compareTo);

    List<TimelineSegment> segments = new ArrayList<>();
    for (int i = 0; i < points.size() - 1; i++) {
      double s = points.get(i);
      double e = points.get(i + 1);
      double length = round(e - s);
      if (length <= 0) {
        continue;
      }

      Integer chosenIdx = null;
      RenderBlockSpec chosenBlock = null;
      for (int idx = 0; idx < blocks.size(); idx++) {
        RenderBlockSpec block = blocks.get(idx);
        if (block.startSec() - 1e-3 <= s && block.endSec() + 1e-3 >= e) {
          if (chosenBlock == null
              || block.track() > chosenBlock.track()
              || (block.track() == chosenBlock.track() && block.startSec() >= chosenBlock.startSec())) {
            chosenBlock = block;
            chosenIdx = idx;
          }
        }
      }

      if (chosenBlock != null) {
        double cropStart = chosenBlock.cropStart() != null ? chosenBlock.cropStart() : 0;
        segments.add(new TimelineSegment(chosenIdx, round(cropStart + (s - chosenBlock.startSec())), length));
      } else {
        segments.add(new TimelineSegment(null, 0, length));
      }
    }
    return segments;
  }

  private static double round(double n) {
    return Math.round(n * 1000) / 1000.0;
  }

  // ---------------------------------------------------------------------------
  // Full template render
  // ---------------------------------------------------------------------------

  public static class RenderException extends RuntimeException {
    public RenderException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * Render a template end-to-end: generate every block, then composite the clips across tracks
   * over the base audio and produce a thumbnail. Throws on the first block that fails (after any
   * baked-clip fallback is exhausted) so callers can mark the render FAILED.
   */
  public RenderResult renderTemplate(RenderTemplateRequest params) {
    List<RenderBlockSpec> blocks = params.getBlocks();
    if (blocks.isEmpty()) {
      throw new RenderException("Template has no video blocks to render.", null);
    }

    // Pre-download all required avatar face buffers.
    Set<Integer> uniqueSlots = new HashSet<>();
    for (RenderBlockSpec b : blocks) {
      uniqueSlots.add(b.avatarSlot());
    }
    Map<Integer, byte[]> faceCache = new ConcurrentHashMap<>();
    List<RenderAvatarSpec> avatars = params.getAvatars();
    ExecutorService faceExecutor = Executors.newFixedThreadPool(Math.max(1, uniqueSlots.size()));
    try {
      List<Future<?>> faceFutures = new ArrayList<>();
      for (int slot : uniqueSlots) {
        faceFutures.add(
            faceExecutor.submit(
                () -> {
                  RenderAvatarSpec avatar = slot < avatars.size() ? avatars.get(slot) : null;
                  if (avatar != null && avatar.faceKey() != null) {
                    faceCache.put(slot, storage.downloadObject(avatar.faceKey()));
                  }
                }));
      }
      awaitAll(faceFutures);
    } finally {
      faceExecutor.shutdown();
    }

    // Deduplicate generation by linkGroupId + avatarSlot: blocks sharing a linkGroupId reference
    // the same generated content, so we only call OpenRouter once per unique group.
    ConcurrentHashMap<String, ClipResult> linkClipCache = new ConcurrentHashMap<>();

    int concurrency = Math.max(1, Math.min(appProperties.getRender().getBlockConcurrency(), blocks.size()));
    ExecutorService blockExecutor = Executors.newFixedThreadPool(concurrency);
    List<Future<BlockOutcome>> futures = new ArrayList<>();
    try {
      for (int idx = 0; idx < blocks.size(); idx++) {
        int i = idx;
        futures.add(
            blockExecutor.submit(() -> processBlock(blocks.get(i), i, params, faceCache, linkClipCache, blockExecutor)));
      }
      List<BlockOutcome> outcomes = new ArrayList<>();
      for (Future<BlockOutcome> f : futures) {
        try {
          outcomes.add(f.get());
        } catch (ExecutionException e) {
          Throwable cause = e.getCause() != null ? e.getCause() : e;
          throw cause instanceof RuntimeException re ? re : new RenderException(cause.getMessage(), cause);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RenderException("Interrupted while rendering blocks", e);
        }
      }

      List<byte[]> clips = outcomes.stream().map(BlockOutcome::buffer).toList();
      double totalCost = outcomes.stream().mapToDouble(BlockOutcome::cost).sum();

      List<TimelineSegment> segments = new ArrayList<>(buildTimelineSegments(blocks));

      List<RenderAudioClipSpec> audioClips = params.getAudioClips();
      List<AudioPart> audioParts = new ArrayList<>();
      for (RenderAudioClipSpec clip : audioClips) {
        double dur = clip.duration() != null ? clip.duration() : 0;
        double cropStart = clip.cropStart() != null ? clip.cropStart() : 0;
        Double cropEnd = clip.cropEnd() != null ? clip.cropEnd() : (dur > 0 ? dur : null);
        double length = cropEnd != null ? Math.max(0.05, cropEnd - cropStart) : 1;
        byte[] data = storage.downloadObject(clip.audioKey());
        audioParts.add(new AudioPart(data, round(Math.max(0, clip.startSec())), round(Math.max(0, cropStart)), round(length)));
      }

      double videoEnd = round(Math.max(0, blocks.stream().mapToDouble(RenderBlockSpec::endSec).max().orElse(0)));
      double audioEnd =
          round(Math.max(0, audioParts.stream().mapToDouble(a -> a.startSec() + a.length()).max().orElse(0)));
      if (audioEnd > videoEnd + 0.001) {
        segments.add(new TimelineSegment(null, 0, round(audioEnd - videoEnd)));
      }

      RenderBlockSpec base =
          blocks.stream()
              .sorted((a, b) -> a.startSec() != b.startSec() ? Double.compare(a.startSec(), b.startSec()) : Integer.compare(a.track(), b.track()))
              .findFirst()
              .orElseThrow();
      Dimensions dims = aspectToDims(base.aspectRatio());

      byte[] videoBuffer = ffmpeg.stitchTimeline(clips, segments, audioParts, dims.width(), dims.height(), 30);

      byte[] thumbnailBuffer;
      String thumbnailContentType = "image/jpeg";
      if (params.isAiThumbnail()) {
        AiThumbnailResult ai = tryAiThumbnail(blocks, base.aspectRatio(), params.getThumbnailPrompt(), avatars);
        if (ai != null) {
          thumbnailBuffer = ai.data();
          thumbnailContentType = ai.contentType();
          totalCost += ai.cost();
        } else {
          thumbnailBuffer = ffmpeg.generateThumbnail(videoBuffer, 1);
        }
      } else {
        thumbnailBuffer = ffmpeg.generateThumbnail(videoBuffer, 1);
      }

      return new RenderResult(videoBuffer, "video/mp4", thumbnailBuffer, thumbnailContentType, totalCost);
    } finally {
      blockExecutor.shutdown();
    }
  }

  private record BlockOutcome(byte[] buffer, double cost) {}

  private BlockOutcome processBlock(
      RenderBlockSpec block,
      int idx,
      RenderTemplateRequest params,
      Map<Integer, byte[]> faceCache,
      ConcurrentHashMap<String, ClipResult> linkClipCache,
      ExecutorService blockExecutor) {
    ProgressCallback onProgress = params.getOnProgress();
    ArtifactsCallback onArtifacts = params.getOnArtifacts();
    java.util.function.BiConsumer<RenderBlockPhase, Integer> report =
        (phase, attempt) -> {
          if (block.id() != null && onProgress != null) {
            onProgress.onProgress(block.id(), phase, attempt, null);
          }
        };
    java.util.function.BiConsumer<RenderBlockPhase, String> reportError =
        (phase, error) -> {
          if (block.id() != null && onProgress != null) {
            onProgress.onProgress(block.id(), phase, null, error);
          }
        };

    String blockId = block.id() != null ? block.id() : "";
    BlockResumeInfo resumeInfo = params.getResume() != null ? params.getResume().get(blockId) : null;

    // Admin-uploaded raw video: use it as-is, always.
    if (block.sourceVideoKey() != null) {
      byte[] buffer = storage.downloadObject(block.sourceVideoKey());
      report.accept(RenderBlockPhase.REUSED, null);
      return new BlockOutcome(buffer, 0);
    }

    // Reuse admin-baked clip only when not forcing regeneration.
    if (block.videoKey() != null && !params.isForceRegenerate()) {
      byte[] buffer = storage.downloadObject(block.videoKey());
      report.accept(RenderBlockPhase.REUSED, null);
      return new BlockOutcome(buffer, 0);
    }

    // Resume: this block already produced a clip in a prior attempt.
    if (resumeInfo != null && resumeInfo.videoKey() != null) {
      byte[] buffer = storage.downloadObject(resumeInfo.videoKey());
      if (onArtifacts != null) {
        onArtifacts.onArtifacts(blockId, new BlockArtifacts(resumeInfo.videoKey(), null, null));
      }
      report.accept(RenderBlockPhase.COMPLETED, null);
      return new BlockOutcome(buffer, 0);
    }

    byte[] face = faceCache.get(block.avatarSlot());

    ImageBytes cachedStart =
        resumeInfo != null && resumeInfo.swappedStartKey() != null
            ? new ImageBytes(storage.downloadObject(resumeInfo.swappedStartKey()), null)
            : null;
    ImageBytes cachedEnd =
        resumeInfo != null && resumeInfo.swappedEndKey() != null
            ? new ImageBytes(storage.downloadObject(resumeInfo.swappedEndKey()), null)
            : null;
    if (cachedStart != null) {
      cachedStart = new ImageBytes(cachedStart.data(), sniffImageMime(cachedStart.data()));
    }
    if (cachedEnd != null) {
      cachedEnd = new ImageBytes(cachedEnd.data(), sniffImageMime(cachedEnd.data()));
    }
    ImageBytes finalCachedStart = cachedStart;
    ImageBytes finalCachedEnd = cachedEnd;

    SwappedFramesCallback onSwapped =
        (start, end) -> {
          String swappedStartKey =
              start != null ? storage.uploadBuffer(start.data(), start.mime(), "templates/render-frames", ext(start.mime())) : null;
          String swappedEndKey =
              end != null ? storage.uploadBuffer(end.data(), end.mime(), "templates/render-frames", ext(end.mime())) : null;
          if ((swappedStartKey != null || swappedEndKey != null) && onArtifacts != null) {
            onArtifacts.onArtifacts(blockId, new BlockArtifacts(null, swappedStartKey, swappedEndKey));
          }
        };

    try {
      boolean lipsync = block.lipsync() && OpenRouterClient.supportsAudioLipsync(block.model());
      ClipResult clip;
      if (lipsync) {
        String lipsyncAudioUrl = buildBlockLipsyncAudio(params.getAudioClips(), block.startSec(), block.endSec());
        clip =
            renderBlockClip(
                block,
                face,
                new RenderBlockClipOptions(
                    !params.isForceRegenerate(),
                    lipsyncAudioUrl,
                    (phase, attempt) -> report.accept(phase, attempt),
                    finalCachedStart,
                    finalCachedEnd,
                    onSwapped));
      } else {
        String dedupeKey = block.linkGroupId() != null ? block.linkGroupId() + ":" + block.avatarSlot() : null;
        if (dedupeKey != null) {
          report.accept(RenderBlockPhase.VIDEO_GENERATION, null);
          // computeIfAbsent itself serializes concurrent callers for the same key (only one
          // thread runs the mapping function; others block on it) — do NOT submit a nested task
          // to blockExecutor here, since that can deadlock a saturated pool (both worker threads
          // busy on outer block tasks, with no thread free to run the nested dedupe task).
          clip = linkClipCache.computeIfAbsent(
              dedupeKey,
              k ->
                  renderBlockClip(
                      block,
                      face,
                      new RenderBlockClipOptions(
                          !params.isForceRegenerate(),
                          null,
                          (phase, attempt) -> report.accept(phase, attempt),
                          finalCachedStart,
                          finalCachedEnd,
                          onSwapped)));
        } else {
          clip =
              renderBlockClip(
                  block,
                  face,
                  new RenderBlockClipOptions(
                      !params.isForceRegenerate(),
                      null,
                      (phase, attempt) -> report.accept(phase, attempt),
                      finalCachedStart,
                      finalCachedEnd,
                      onSwapped));
        }
      }

      String videoKey = storage.uploadBuffer(clip.buffer(), clip.contentType(), "templates/render-clips", "mp4");
      if (onArtifacts != null) {
        onArtifacts.onArtifacts(blockId, new BlockArtifacts(videoKey, null, null));
      }
      report.accept(RenderBlockPhase.COMPLETED, null);
      return new BlockOutcome(clip.buffer(), clip.cost());
    } catch (Exception e) {
      String msg = e.getMessage() != null ? e.getMessage() : e.toString();
      if (block.videoKey() != null) {
        byte[] buffer = storage.downloadObject(block.videoKey());
        reportError.accept(RenderBlockPhase.FELL_BACK, msg);
        return new BlockOutcome(buffer, 0);
      }
      reportError.accept(RenderBlockPhase.FAILED, msg);
      throw e instanceof RuntimeException re ? re : new RenderException(msg, e);
    }
  }

  private record AiThumbnailResult(byte[] data, String contentType, double cost) {}

  private AiThumbnailResult tryAiThumbnail(
      List<RenderBlockSpec> blocks, String aspectRatio, String thumbnailPrompt, List<RenderAvatarSpec> avatars) {
    List<String> models = new ArrayList<>();
    models.add(appProperties.getOpenrouter().getThumbnailModel());
    for (String m : appProperties.getOpenrouter().getThumbnailFallbackModels()) {
      if (m != null && !models.contains(m)) {
        models.add(m);
      }
    }
    int attemptsPerModel = 2;

    List<byte[]> faces = new ArrayList<>();
    for (RenderAvatarSpec a : avatars) {
      if (a != null && a.faceKey() != null) {
        try {
          faces.add(storage.downloadObject(a.faceKey()));
        } catch (Exception ignored) {
          // A missing/failed face just means the thumbnail is generated without that reference.
        }
      }
    }

    for (String model : models) {
      for (int attempt = 1; attempt <= attemptsPerModel; attempt++) {
        try {
          return generateAiThumbnail(blocks, aspectRatio, thumbnailPrompt, faces, model);
        } catch (Exception e) {
          if (attempt < attemptsPerModel) {
            try {
              Thread.sleep(1200L * attempt);
            } catch (InterruptedException ie) {
              Thread.currentThread().interrupt();
              return null;
            }
          }
        }
      }
    }
    return null;
  }

  /**
   * AI-generate a cover thumbnail. Uses the admin's thumbnailPrompt when provided (else falls
   * back to a description built from the block prompts), passing avatar faces as references.
   */
  private AiThumbnailResult generateAiThumbnail(
      List<RenderBlockSpec> blocks, String aspectRatio, String thumbnailPrompt, List<byte[]> faces, String model) {
    List<String> scenes =
        blocks.stream()
            .sorted((a, b) -> Double.compare(a.startSec(), b.startSec()))
            .map(b -> b.prompt() != null ? b.prompt().trim() : null)
            .filter(p -> p != null && !p.isEmpty())
            .toList();

    String custom = thumbnailPrompt != null ? thumbnailPrompt.trim() : null;
    if ((custom == null || custom.isEmpty()) && scenes.isEmpty()) {
      throw new RenderException("No thumbnail description or block prompts to build a thumbnail from.", null);
    }

    String instruction;
    if (custom != null && !custom.isEmpty()) {
      instruction = custom;
    } else {
      String joined = String.join("; ", scenes);
      if (joined.length() > 1500) {
        joined = joined.substring(0, 1500) + "…";
      }
      instruction =
          "Design a single, eye-catching cover thumbnail image that represents this short video. The video's scenes are: "
              + joined
              + ".";
    }

    String refNote =
        !faces.isEmpty()
            ? " Feature the person shown in the reference image(s) as the main subject, preserving their exact likeness and identity."
            : "";
    boolean isCustom = custom != null && !custom.isEmpty();
    String quality =
        isCustom
            ? " Cinematic, high quality, cohesive composition."
            : " Cinematic, high quality, cohesive composition, no text, captions or watermarks.";
    String prompt = instruction + refNote + quality;

    List<ImageRef> references = faces.stream().map(f -> new ImageRef(toDataUrl(f, sniffImageMime(f)))).toList();
    GeneratedImage img =
        openRouter.generateImage(
            new GenerateImageParams(
                model, prompt, null, aspectRatio != null ? aspectRatio : "16:9", references.isEmpty() ? null : references));
    return new AiThumbnailResult(img.data(), img.contentType(), img.cost() != null ? img.cost() : 0);
  }

  private static void awaitAll(List<Future<?>> futures) {
    for (Future<?> f : futures) {
      try {
        f.get();
      } catch (ExecutionException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        throw cause instanceof RuntimeException re ? re : new RenderException(cause.getMessage(), cause);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RenderException("Interrupted", e);
      }
    }
  }
}
