package com.pixovid.backend.template;

import com.pixovid.backend.generation.GenerationStatus;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Port of apps/backend/src/index.ts's failOrphanedRenders(): a server restart orphans any
 * IN_PROGRESS render (the detached background task that was running it is gone), so mark them
 * (and their still-running blocks) FAILED on boot rather than leaving them stuck forever.
 */
@Component
public class OrphanRenderReconciler implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(OrphanRenderReconciler.class);

  private static final List<RenderBlockPhase> IN_PROGRESS_PHASES =
      List.of(
          RenderBlockPhase.QUEUED,
          RenderBlockPhase.FACE_SWAP,
          RenderBlockPhase.VIDEO_GENERATION,
          RenderBlockPhase.RETRYING,
          RenderBlockPhase.STITCHING);

  private final TemplateRenderRepository renders;
  private final TemplateRenderBlockRepository renderBlocks;

  public OrphanRenderReconciler(TemplateRenderRepository renders, TemplateRenderBlockRepository renderBlocks) {
    this.renders = renders;
    this.renderBlocks = renderBlocks;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    try {
      List<TemplateRender> orphaned = renders.findByStatus(GenerationStatus.IN_PROGRESS);
      if (orphaned.isEmpty()) {
        return;
      }
      List<String> orphanedIds = orphaned.stream().map(TemplateRender::getId).toList();
      for (TemplateRender render : orphaned) {
        render.setStatus(GenerationStatus.FAILED);
        render.setError("Render was interrupted by a server restart. Please try again.");
        renders.save(render);
      }
      for (TemplateRenderBlock block : renderBlocks.findByRender_IdInAndPhaseIn(orphanedIds, IN_PROGRESS_PHASES)) {
        block.setPhase(RenderBlockPhase.FAILED);
        block.setError("Interrupted by server restart");
        renderBlocks.save(block);
      }
      log.info("Marked {} interrupted render(s) as failed on startup.", orphaned.size());
    } catch (Exception e) {
      log.error("Failed to reconcile orphaned renders on startup: {}", e.getMessage(), e);
    }
  }
}
