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
import com.pixovid.backend.template.dto.TemplateRenderResponse;
import com.pixovid.backend.template.dto.TemplateResponse;
import com.pixovid.backend.template.render.RenderAudioClipSpec;
import com.pixovid.backend.template.render.RenderAvatarSpec;
import com.pixovid.backend.template.render.RenderBlockSpec;
import com.pixovid.backend.template.render.TemplateRenderRunner;
import com.pixovid.backend.user.User;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Port of apps/backend/src/routes/templates.ts's templatesRouter: published templates
 * (read-only for users) + generating a personalised render from one.
 */
@RestController
@RequestMapping("/api/templates")
public class TemplatesController {

  private final TemplateRepository templates;
  private final TemplateRenderRepository renders;
  private final TemplateBlockRepository blocks;
  private final TemplateAudioClipRepository audioClips;
  private final TemplateRenderBlockRepository renderBlockRepository;
  private final AvatarRepository avatars;
  private final StorageService storage;
  private final CreditsService creditsService;
  private final TemplateRenderRunner renderRunner;

  public TemplatesController(
      TemplateRepository templates,
      TemplateRenderRepository renders,
      TemplateBlockRepository blocks,
      TemplateAudioClipRepository audioClips,
      TemplateRenderBlockRepository renderBlockRepository,
      AvatarRepository avatars,
      StorageService storage,
      CreditsService creditsService,
      TemplateRenderRunner renderRunner) {
    this.templates = templates;
    this.renders = renders;
    this.blocks = blocks;
    this.audioClips = audioClips;
    this.renderBlockRepository = renderBlockRepository;
    this.avatars = avatars;
    this.storage = storage;
    this.creditsService = creditsService;
    this.renderRunner = renderRunner;
  }

  @GetMapping
  public List<TemplateResponse> list() {
    return templates.findByPublishedTrue().stream()
        .map(
            t ->
                TemplateResponse.of(t, storage, null, null, blocks.findByTemplateIdOrderByOrderAsc(t.getId()).size()))
        .toList();
  }

  @GetMapping("/{id}")
  public TemplateResponse get(@PathVariable String id) {
    Template template = templates.findByIdAndPublishedTrue(id).orElseThrow(() -> new NotFoundException("Not found"));
    return TemplateResponse.of(template, storage, null, null, blocks.findByTemplateIdOrderByOrderAsc(id).size());
  }

  @GetMapping("/{id}/renders")
  public List<TemplateRenderResponse> renders(@AuthenticationPrincipal User user, @PathVariable String id) {
    return renders.findByTemplateIdAndUserIdOrderByCreatedAtDesc(id, user.getId()).stream()
        .map(r -> TemplateRenderResponse.of(r, storage))
        .toList();
  }

  public record RenderRequest(List<String> avatarIds) {}

  /** Generate a personalised video from a template using the user's own avatars. */
  @PostMapping("/{id}/render")
  public ResponseEntity<?> render(
      @AuthenticationPrincipal User user, @PathVariable String id, @RequestBody RenderRequest request) {
    Template template = templates.findByIdAndPublishedTrue(id).orElseThrow(() -> new NotFoundException("Not found"));

    List<String> avatarIds = request.avatarIds();
    if (avatarIds == null || avatarIds.isEmpty() || avatarIds.size() > 2) {
      throw new BadRequestException("Select 1 or 2 avatars.");
    }
    List<TemplateBlock> templateBlocks = blocks.findByTemplateIdOrderByOrderAsc(id);
    if (templateBlocks.isEmpty()) {
      throw new BadRequestException("This template has no video blocks.");
    }
    if (avatarIds.size() != template.getAvatarSlots()) {
      throw new BadRequestException("This template needs exactly " + template.getAvatarSlots() + " avatar(s).");
    }

    List<Avatar> userAvatars = avatars.findByIdIn(avatarIds);
    Set<String> foundIds = new HashSet<>();
    for (Avatar a : userAvatars) {
      if (a.getUser().getId().equals(user.getId())) {
        foundIds.add(a.getId());
      }
    }
    if (foundIds.size() != avatarIds.size()) {
      throw new BadRequestException("One or more selected avatars were not found.");
    }
    List<Avatar> orderedAvatars = avatarIds.stream().map(aid -> findAvatar(userAvatars, aid)).toList();

    int cost = creditsService.actionCost(GenerationAction.TEMPLATE_RENDER);
    if (creditsService.getBalance(user.getId()) < cost) {
      return ResponseEntity.status(402).body(Map.of("error", "Not enough credits. A render costs " + cost + " credits."));
    }

    TemplateRender render = new TemplateRender();
    render.setTemplate(template);
    render.setUser(user);
    render.setAvatarIds(avatarIds);
    render.setAvatars(new HashSet<>(orderedAvatars));
    render.setStatus(GenerationStatus.IN_PROGRESS);
    render = renders.save(render);
    String renderId = render.getId();

    try {
      creditsService.spendCredits(
          user.getId(), cost, CreditsService.LedgerRef.of("template_render", renderId, "Template render"));
    } catch (InsufficientCreditsException e) {
      render.setStatus(GenerationStatus.FAILED);
      render.setError("Not enough credits.");
      renders.save(render);
      return ResponseEntity.status(402).body(Map.of("error", "Not enough credits. A render costs " + cost + " credits."));
    }

    seedRenderBlockRows(render, templateBlocks);

    List<RenderBlockSpec> specs = templateBlocks.stream().map(RenderBlockSpec::from).toList();
    List<RenderAvatarSpec> avatarSpecs = orderedAvatars.stream().map(a -> new RenderAvatarSpec(a.getFaceKey())).toList();
    List<RenderAudioClipSpec> audioSpecs =
        audioClips.findByTemplateIdOrderByOrderAsc(id).stream().map(RenderAudioClipSpec::from).toList();

    // Run the render in the background and return immediately — the client navigates to
    // /generation/:id and polls for progress instead of holding a long-lived request open.
    renderRunner
        .runAndStoreRenderAsync(
            TemplateRenderRunner.RunParams.builder()
                .renderId(renderId)
                .blocks(specs)
                .orderedAvatars(avatarSpecs)
                .audioClips(audioSpecs)
                // Same as export: AI cover thumbnail, with the USER's avatar as the reference.
                .aiThumbnail(true)
                .thumbnailPrompt(template.getThumbnailPrompt())
                .forceRegenerate(true)
                .trackProgress(true)
                .build())
        .exceptionally(
            ex -> {
              creditsService.refundCredits(
                  user.getId(), cost, "template_render", renderId, "Refund: template render failed");
              return null;
            });

    return ResponseEntity.status(201).body(TemplateRenderResponse.of(render, storage));
  }

  private void seedRenderBlockRows(TemplateRender render, List<TemplateBlock> templateBlocks) {
    List<TemplateRenderBlock> rows = new ArrayList<>();
    for (int i = 0; i < templateBlocks.size(); i++) {
      TemplateBlock b = templateBlocks.get(i);
      TemplateRenderBlock row = new TemplateRenderBlock();
      row.setRender(render);
      row.setBlockId(b.getId());
      row.setOrder(i);
      row.setStartSec(b.getStartSec());
      row.setEndSec(b.getEndSec());
      String label =
          b.getSourceVideoKey() != null
              ? "Uploaded clip"
              : (b.getPrompt() != null && !b.getPrompt().isBlank())
                  ? b.getPrompt().trim().substring(0, Math.min(80, b.getPrompt().trim().length()))
                  : "Clip " + (i + 1);
      row.setLabel(label);
      row.setPhase(RenderBlockPhase.QUEUED);
      rows.add(row);
    }
    renderBlockRepository.saveAll(rows);
  }

  private static Avatar findAvatar(List<Avatar> avatars, String id) {
    return avatars.stream().filter(a -> a.getId().equals(id)).findFirst().orElseThrow();
  }
}
