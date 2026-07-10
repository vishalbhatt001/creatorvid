package com.pixovid.backend.template;

import com.pixovid.backend.avatar.Avatar;
import com.pixovid.backend.avatar.AvatarRepository;
import com.pixovid.backend.common.BadRequestException;
import com.pixovid.backend.common.NotFoundException;
import com.pixovid.backend.credits.CreditsService;
import com.pixovid.backend.credits.GenerationAction;
import com.pixovid.backend.credits.InsufficientCreditsException;
import com.pixovid.backend.generation.GenerationStatus;
import com.pixovid.backend.storage.StorageService;
import com.pixovid.backend.template.dto.TemplateRenderBlockResponse;
import com.pixovid.backend.template.dto.TemplateRenderResponse;
import com.pixovid.backend.template.render.RenderAudioClipSpec;
import com.pixovid.backend.template.render.RenderAvatarSpec;
import com.pixovid.backend.template.render.RenderBlockSpec;
import com.pixovid.backend.template.render.TemplateRenderRunner;
import com.pixovid.backend.user.User;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Port of apps/backend/src/routes/templates.ts's templateRendersRouter: the user's generated template videos. */
@RestController
@RequestMapping("/api/template-renders")
public class TemplateRendersController {

  private final TemplateRenderRepository renders;
  private final TemplateRenderBlockRepository renderBlocks;
  private final TemplateRepository templates;
  private final TemplateBlockRepository blocks;
  private final TemplateAudioClipRepository audioClips;
  private final AvatarRepository avatars;
  private final StorageService storage;
  private final CreditsService creditsService;
  private final TemplateRenderRunner renderRunner;

  public TemplateRendersController(
      TemplateRenderRepository renders,
      TemplateRenderBlockRepository renderBlocks,
      TemplateRepository templates,
      TemplateBlockRepository blocks,
      TemplateAudioClipRepository audioClips,
      AvatarRepository avatars,
      StorageService storage,
      CreditsService creditsService,
      TemplateRenderRunner renderRunner) {
    this.renders = renders;
    this.renderBlocks = renderBlocks;
    this.templates = templates;
    this.blocks = blocks;
    this.audioClips = audioClips;
    this.avatars = avatars;
    this.storage = storage;
    this.creditsService = creditsService;
    this.renderRunner = renderRunner;
  }

  @GetMapping
  public List<TemplateRenderResponse> list(@AuthenticationPrincipal User user) {
    return renders.findByUserIdFetchTemplateOrderByCreatedAtDesc(user.getId()).stream()
        .map(r -> TemplateRenderResponse.of(r, storage, null, r.getTemplate().getName()))
        .toList();
  }

  @GetMapping("/{id}")
  public TemplateRenderResponse get(@AuthenticationPrincipal User user, @PathVariable String id) {
    TemplateRender render =
        renders.findByIdAndUserIdFetchTemplate(id, user.getId()).orElseThrow(() -> new NotFoundException("Not found"));
    List<TemplateRenderBlockResponse> blockRows =
        renderBlocks.findByRenderIdOrderByOrderAsc(id).stream().map(TemplateRenderBlockResponse::of).toList();
    return TemplateRenderResponse.of(render, storage, blockRows, render.getTemplate().getName());
  }

  /** Retry a failed render in place: completed blocks keep their clip; only the rest re-run. */
  @PostMapping("/{id}/retry")
  public ResponseEntity<?> retry(@AuthenticationPrincipal User user, @PathVariable String id) {
    TemplateRender render = renders.findByIdAndUserId(id, user.getId()).orElseThrow(() -> new NotFoundException("Not found"));
    if (render.getStatus() == GenerationStatus.IN_PROGRESS) {
      return ResponseEntity.status(409).body(Map.of("error", "This render is still in progress."));
    }

    Template template =
        templates.findByIdAndPublishedTrue(render.getTemplate().getId()).orElse(null);
    List<TemplateBlock> templateBlocks = template != null ? blocks.findByTemplateIdOrderByOrderAsc(template.getId()) : List.of();
    if (template == null || templateBlocks.isEmpty()) {
      throw new BadRequestException("This template is no longer available.");
    }

    List<Avatar> ownedAvatars = avatars.findByIdIn(render.getAvatarIds());
    Set<String> foundIds = new HashSet<>();
    for (Avatar a : ownedAvatars) {
      if (a.getUser().getId().equals(user.getId())) {
        foundIds.add(a.getId());
      }
    }
    if (foundIds.size() != render.getAvatarIds().size()) {
      throw new BadRequestException("One or more of this render's avatars no longer exist.");
    }
    List<Avatar> orderedAvatars =
        render.getAvatarIds().stream()
            .map(aid -> ownedAvatars.stream().filter(a -> a.getId().equals(aid)).findFirst().orElseThrow())
            .toList();

    // A FAILED render was already refunded, so retrying it re-charges. Retrying a COMPLETED
    // render reuses the existing paid result — no charge.
    int cost = creditsService.actionCost(GenerationAction.TEMPLATE_RENDER);
    boolean shouldCharge = render.getStatus() == GenerationStatus.FAILED;
    if (shouldCharge && creditsService.getBalance(user.getId()) < cost) {
      return ResponseEntity.status(402).body(Map.of("error", "Not enough credits. A render costs " + cost + " credits."));
    }

    render.setStatus(GenerationStatus.IN_PROGRESS);
    render.setError(null);
    render = renders.save(render);
    String renderId = render.getId();

    if (shouldCharge) {
      try {
        creditsService.spendCredits(
            user.getId(), cost, CreditsService.LedgerRef.of("template_render", renderId, "Template render (retry)"));
      } catch (InsufficientCreditsException e) {
        render.setStatus(GenerationStatus.FAILED);
        render.setError("Not enough credits.");
        renders.save(render);
        return ResponseEntity.status(402).body(Map.of("error", "Not enough credits. A render costs " + cost + " credits."));
      }
    }

    for (TemplateRenderBlock row : renderBlocks.findByRenderIdAndPhaseNot(renderId, RenderBlockPhase.COMPLETED)) {
      row.setPhase(RenderBlockPhase.QUEUED);
      row.setAttempt(0);
      row.setError(null);
      renderBlocks.save(row);
    }

    List<RenderBlockSpec> specs = templateBlocks.stream().map(RenderBlockSpec::from).toList();
    List<RenderAvatarSpec> avatarSpecs = orderedAvatars.stream().map(a -> new RenderAvatarSpec(a.getFaceKey())).toList();
    List<RenderAudioClipSpec> audioSpecs =
        audioClips.findByTemplateIdOrderByOrderAsc(template.getId()).stream().map(RenderAudioClipSpec::from).toList();

    boolean chargeOnFailureRefund = shouldCharge;
    renderRunner
        .runAndStoreRenderAsync(
            TemplateRenderRunner.RunParams.builder()
                .renderId(renderId)
                .blocks(specs)
                .orderedAvatars(avatarSpecs)
                .audioClips(audioSpecs)
                .aiThumbnail(true)
                .thumbnailPrompt(template.getThumbnailPrompt())
                .forceRegenerate(true)
                .trackProgress(true)
                .build())
        .exceptionally(
            ex -> {
              if (chargeOnFailureRefund) {
                creditsService.refundCredits(
                    user.getId(), cost, "template_render", renderId, "Refund: template render failed");
              }
              return null;
            });

    List<TemplateRenderBlockResponse> blockRows =
        renderBlocks.findByRenderIdOrderByOrderAsc(renderId).stream().map(TemplateRenderBlockResponse::of).toList();
    return ResponseEntity.ok(TemplateRenderResponse.of(render, storage, blockRows, template.getName()));
  }
}
