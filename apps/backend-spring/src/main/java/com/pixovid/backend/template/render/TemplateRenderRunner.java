package com.pixovid.backend.template.render;

import com.pixovid.backend.common.MediaUtils;
import com.pixovid.backend.generation.GenerationStatus;
import com.pixovid.backend.storage.StorageService;
import com.pixovid.backend.template.TemplateRender;
import com.pixovid.backend.template.TemplateRenderBlock;
import com.pixovid.backend.template.TemplateRenderBlockRepository;
import com.pixovid.backend.template.TemplateRenderRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.Builder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Port of apps/backend/src/lib/runRender.ts: executes a template render synchronously and
 * persists the result on the given TemplateRender row (marking it COMPLETED/FAILED).
 */
@Service
public class TemplateRenderRunner {

  private static final Logger log = LoggerFactory.getLogger(TemplateRenderRunner.class);

  private final TemplateRenderEngine engine;
  private final TemplateRenderRepository renders;
  private final TemplateRenderBlockRepository renderBlocks;
  private final StorageService storage;

  public TemplateRenderRunner(
      TemplateRenderEngine engine,
      TemplateRenderRepository renders,
      TemplateRenderBlockRepository renderBlocks,
      StorageService storage) {
    this.engine = engine;
    this.renders = renders;
    this.renderBlocks = renderBlocks;
    this.storage = storage;
  }

  public record RunResult(String videoKey, String thumbnailKey, double cost) {}

  @Builder
  public static class RunParams {
    String renderId;
    List<RenderBlockSpec> blocks;
    List<RenderAvatarSpec> orderedAvatars;
    @Builder.Default List<RenderAudioClipSpec> audioClips = List.of();
    /** AI-generate the cover thumbnail (with the avatar as a reference image). */
    boolean aiThumbnail;
    String thumbnailPrompt;
    /** When true, re-generate every block even if a baked videoKey exists (user renders). */
    boolean forceRegenerate;
    /** When true, persist per-block phase updates so the live /generation/:id page can show progress. */
    boolean trackProgress;
  }

  /**
   * Fire-and-forget entry point for user renders/retries: runs the render in the background
   * (mirroring the Node backend's {@code void runAndStoreRender(...).catch(...)} pattern) and
   * returns immediately so the caller (an HTTP request thread) isn't held open for minutes.
   */
  @Async
  public CompletableFuture<RunResult> runAndStoreRenderAsync(RunParams params) {
    return CompletableFuture.completedFuture(runAndStoreRender(params));
  }

  /** Blocking entry point, used by the admin export flow which awaits the result synchronously. */
  public RunResult runAndStoreRender(RunParams params) {
    ProgressCallback onProgress =
        params.trackProgress
            ? (blockId, phase, attempt, error) -> writeProgress(params.renderId, blockId, phase, attempt, error)
            : null;
    ArtifactsCallback onArtifacts =
        params.trackProgress ? (blockId, artifacts) -> writeArtifacts(params.renderId, blockId, artifacts) : null;

    // Build the resume map from any artifacts a previous attempt already persisted. A block with
    // a stored clip is reused whole; stored swapped frames let an unfinished block skip re-swapping.
    Map<String, BlockResumeInfo> resume = null;
    if (params.trackProgress) {
      resume = new HashMap<>();
      for (TemplateRenderBlock row : renderBlocks.findByRenderIdOrderByOrderAsc(params.renderId)) {
        resume.put(
            row.getBlockId(),
            new BlockResumeInfo(
                row.getPhase() == com.pixovid.backend.template.RenderBlockPhase.COMPLETED ? row.getVideoKey() : null,
                row.getSwappedStartKey(),
                row.getSwappedEndKey()));
      }
    }

    try {
      RenderResult result =
          engine.renderTemplate(
              RenderTemplateRequest.builder()
                  .blocks(params.blocks)
                  .avatars(params.orderedAvatars)
                  .audioClips(params.audioClips)
                  .aiThumbnail(params.aiThumbnail)
                  .thumbnailPrompt(params.thumbnailPrompt)
                  .forceRegenerate(params.forceRegenerate)
                  .onProgress(onProgress)
                  .onArtifacts(onArtifacts)
                  .resume(resume)
                  .build());

      String videoKey = storage.uploadBuffer(result.videoBuffer(), result.contentType(), "templates/renders", "mp4");
      String thumbnailKey =
          storage.uploadBuffer(
              result.thumbnailBuffer(), result.thumbnailContentType(), "templates/thumbnails",
              MediaUtils.extFromMime(result.thumbnailContentType()));

      updateRenderStatus(params.renderId, GenerationStatus.COMPLETED, videoKey, thumbnailKey, result.cost(), null);
      return new RunResult(videoKey, thumbnailKey, result.cost());
    } catch (Exception e) {
      String message = e.getMessage() != null ? e.getMessage() : "Template render failed";
      log.error("Template render failed: {}", message);
      updateRenderStatus(params.renderId, GenerationStatus.FAILED, null, null, null, message);
      throw e instanceof RuntimeException re ? re : new TemplateRenderEngine.RenderException(message, e);
    }
  }

  // Not @Transactional: called via self-invocation (bypasses the Spring proxy), so the
  // atomicity guarantee here comes from the repository's own save() being individually
  // transactional — sufficient since each write below is a single row.
  void updateRenderStatus(
      String renderId, GenerationStatus status, String videoKey, String thumbnailKey, Double cost, String error) {
    TemplateRender render = renders.findById(renderId).orElseThrow();
    render.setStatus(status);
    if (videoKey != null) {
      render.setVideoKey(videoKey);
    }
    if (thumbnailKey != null) {
      render.setThumbnailKey(thumbnailKey);
    }
    if (cost != null) {
      render.setCost(cost);
    }
    render.setError(error);
    renders.save(render);
  }

  void writeProgress(
      String renderId, String blockId, com.pixovid.backend.template.RenderBlockPhase phase, Integer attempt, String error) {
    if (blockId == null || blockId.isBlank()) {
      return;
    }
    try {
      for (TemplateRenderBlock row : renderBlocks.findByRenderIdAndBlockId(renderId, blockId)) {
        if (phase != null) {
          row.setPhase(phase);
        }
        if (attempt != null) {
          row.setAttempt(attempt);
        }
        if (error != null) {
          row.setError(error);
        }
        renderBlocks.save(row);
      }
    } catch (Exception e) {
      log.warn("Block row update failed: {}", e.getMessage());
    }
  }

  void writeArtifacts(String renderId, String blockId, BlockArtifacts artifacts) {
    if (blockId == null || blockId.isBlank()) {
      return;
    }
    try {
      for (TemplateRenderBlock row : renderBlocks.findByRenderIdAndBlockId(renderId, blockId)) {
        if (artifacts.videoKey() != null) {
          row.setVideoKey(artifacts.videoKey());
        }
        if (artifacts.swappedStartKey() != null) {
          row.setSwappedStartKey(artifacts.swappedStartKey());
        }
        if (artifacts.swappedEndKey() != null) {
          row.setSwappedEndKey(artifacts.swappedEndKey());
        }
        renderBlocks.save(row);
      }
    } catch (Exception e) {
      log.warn("Block row update failed: {}", e.getMessage());
    }
  }
}
