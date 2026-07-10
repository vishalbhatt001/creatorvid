package com.pixovid.backend.template;

import com.pixovid.backend.auth.AdminService;
import com.pixovid.backend.avatar.Avatar;
import com.pixovid.backend.avatar.AvatarRepository;
import com.pixovid.backend.common.BadRequestException;
import com.pixovid.backend.common.MediaUtils;
import com.pixovid.backend.common.NotFoundException;
import com.pixovid.backend.ffmpeg.FfmpegService;
import com.pixovid.backend.generation.GenerationStatus;
import com.pixovid.backend.openrouter.OpenRouterClient;
import com.pixovid.backend.storage.StorageService;
import com.pixovid.backend.template.dto.TemplateAudioClipResponse;
import com.pixovid.backend.template.dto.TemplateBlockResponse;
import com.pixovid.backend.template.dto.TemplateRenderResponse;
import com.pixovid.backend.template.dto.TemplateResponse;
import com.pixovid.backend.template.render.RenderAudioClipSpec;
import com.pixovid.backend.template.render.RenderAvatarSpec;
import com.pixovid.backend.template.render.RenderBlockSpec;
import com.pixovid.backend.template.render.TemplateRenderEngine;
import com.pixovid.backend.template.render.TemplateRenderRunner;
import com.pixovid.backend.user.User;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Port of apps/backend/src/routes/adminTemplates.ts. Every route requires admin (checked per-call via AdminService). */
@RestController
@RequestMapping("/api/admin/templates")
public class AdminTemplatesController {

  private static final int MIN_CLIP = 1; // minimum cropped clip length (seconds)

  private final TemplateRepository templates;
  private final TemplateBlockRepository blocks;
  private final TemplateAudioClipRepository audioClips;
  private final TemplateRenderRepository renders;
  private final TemplateRenderBlockRepository renderBlockRows;
  private final AvatarRepository avatars;
  private final StorageService storage;
  private final FfmpegService ffmpeg;
  private final TemplateRenderEngine renderEngine;
  private final TemplateRenderRunner renderRunner;
  private final AdminService adminService;

  public AdminTemplatesController(
      TemplateRepository templates,
      TemplateBlockRepository blocks,
      TemplateAudioClipRepository audioClips,
      TemplateRenderRepository renders,
      TemplateRenderBlockRepository renderBlockRows,
      AvatarRepository avatars,
      StorageService storage,
      FfmpegService ffmpeg,
      TemplateRenderEngine renderEngine,
      TemplateRenderRunner renderRunner,
      AdminService adminService) {
    this.templates = templates;
    this.blocks = blocks;
    this.audioClips = audioClips;
    this.renders = renders;
    this.renderBlockRows = renderBlockRows;
    this.avatars = avatars;
    this.storage = storage;
    this.ffmpeg = ffmpeg;
    this.renderEngine = renderEngine;
    this.renderRunner = renderRunner;
    this.adminService = adminService;
  }

  // ---------------------------------------------------------------------------
  // Scope helpers
  // ---------------------------------------------------------------------------

  private boolean isSuperAdmin(User user) {
    return adminService.isSuperAdminEmail(user.getEmail());
  }

  /** A superadmin can address any template; a regular admin is restricted to templates they created. */
  private Template ownedTemplate(User user, String id) {
    adminService.requireAdmin(user);
    return (isSuperAdmin(user) ? templates.findById(id) : templates.findByIdAndCreatorId(id, user.getId()))
        .orElseThrow(() -> new NotFoundException("Not found"));
  }

  private void assertOwnedAvatars(List<String> avatarIds, String ownerId) {
    if (avatarIds.size() < 1 || avatarIds.size() > 2) {
      throw new BadRequestException("Select 1 or 2 avatars for the template.");
    }
    long found = avatars.findByIdIn(avatarIds).stream().filter(a -> a.getUser().getId().equals(ownerId)).count();
    if (found != avatarIds.size()) {
      throw new BadRequestException("One or more selected avatars were not found.");
    }
  }

  private byte[] resolveBlockFace(List<String> avatarIds, int avatarSlot, String ownerId) {
    if (avatarSlot >= avatarIds.size()) {
      return null;
    }
    String avatarId = avatarIds.get(avatarSlot);
    Avatar avatar =
        avatars.findByIdIn(List.of(avatarId)).stream()
            .filter(a -> a.getUser().getId().equals(ownerId))
            .findFirst()
            .orElse(null);
    return avatar != null && avatar.getFaceKey() != null ? storage.downloadObject(avatar.getFaceKey()) : null;
  }

  private static double[] clampCrop(double cropStart, double cropEnd, double duration) {
    double cs = Math.min(Math.max(0, cropStart), Math.max(0, duration - MIN_CLIP));
    double ce = Math.min(Math.max(cs + MIN_CLIP, cropEnd), duration);
    return new double[] {cs, ce};
  }

  private String uploadIfPresent(MultipartFile file, String prefix) {
    if (file == null || file.isEmpty()) {
      return null;
    }
    try {
      return storage.uploadBuffer(file.getBytes(), file.getContentType(), prefix, MediaUtils.extFromMime(file.getContentType()));
    } catch (IOException e) {
      throw new BadRequestException("Failed to read uploaded file \"" + file.getOriginalFilename() + "\"");
    }
  }

  // ---------------------------------------------------------------------------
  // Templates
  // ---------------------------------------------------------------------------

  /** A superadmin sees every admin's templates; a regular admin sees only their own. */
  @GetMapping
  public List<TemplateResponse> list(@AuthenticationPrincipal User user) {
    adminService.requireAdmin(user);
    List<Template> list = isSuperAdmin(user) ? templates.findAll() : templates.findByCreatorId(user.getId());
    return list.stream()
        .map(t -> TemplateResponse.of(t, storage, null, null, (int) blocks.countByTemplateId(t.getId())))
        .toList();
  }

  @GetMapping("/{id}")
  public TemplateResponse get(@AuthenticationPrincipal User user, @PathVariable String id) {
    Template template = ownedTemplate(user, id);
    List<TemplateBlockResponse> blockRows =
        blocks.findByTemplateIdOrderByOrderAsc(id).stream().map(b -> TemplateBlockResponse.of(b, storage)).toList();
    List<TemplateAudioClipResponse> audioRows =
        audioClips.findByTemplateIdOrderByOrderAsc(id).stream().map(c -> TemplateAudioClipResponse.of(c, storage)).toList();
    return TemplateResponse.of(template, storage, blockRows, audioRows, blockRows.size());
  }

  /** The admin assigns 1-2 of their own avatars up front; the timeline starts empty. */
  @PostMapping
  public ResponseEntity<TemplateResponse> create(
      @AuthenticationPrincipal User user,
      @RequestParam String name,
      @RequestParam(required = false) String description,
      @RequestParam(required = false) List<String> avatarIds,
      @RequestParam(required = false) String thumbnailPrompt) {
    adminService.requireAdmin(user);
    if (name == null || name.isBlank()) {
      throw new BadRequestException("Name is required");
    }
    List<String> ids = avatarIds != null ? avatarIds : List.of();
    assertOwnedAvatars(ids, user.getId());

    Template template = new Template();
    template.setCreator(user);
    template.setName(name);
    template.setDescription(description);
    template.setAvatarIds(ids);
    template.setAvatarSlots(ids.size());
    template.setThumbnailPrompt(thumbnailPrompt);
    template = templates.save(template);
    return ResponseEntity.status(201).body(TemplateResponse.of(template, storage, List.of(), List.of(), 0));
  }

  @PatchMapping("/{id}")
  public TemplateResponse update(
      @AuthenticationPrincipal User user,
      @PathVariable String id,
      @RequestParam(required = false) String name,
      @RequestParam(required = false) String description,
      @RequestParam(required = false) List<String> avatarIds,
      @RequestParam(required = false) String thumbnailPrompt) {
    Template template = ownedTemplate(user, id);
    if (avatarIds != null) {
      // Validated against the template's creator (a superadmin editing another admin's template
      // assigns that admin's avatars).
      assertOwnedAvatars(avatarIds, template.getCreator().getId());
      template.setAvatarIds(avatarIds);
      template.setAvatarSlots(avatarIds.size());
    }
    if (name != null) {
      template.setName(name);
    }
    if (description != null) {
      template.setDescription(description);
    }
    if (thumbnailPrompt != null) {
      template.setThumbnailPrompt(thumbnailPrompt);
    }
    template = templates.save(template);
    List<TemplateBlockResponse> blockRows =
        blocks.findByTemplateIdOrderByOrderAsc(id).stream().map(b -> TemplateBlockResponse.of(b, storage)).toList();
    List<TemplateAudioClipResponse> audioRows =
        audioClips.findByTemplateIdOrderByOrderAsc(id).stream().map(c -> TemplateAudioClipResponse.of(c, storage)).toList();
    return TemplateResponse.of(template, storage, blockRows, audioRows, blockRows.size());
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@AuthenticationPrincipal User user, @PathVariable String id) {
    Template template = ownedTemplate(user, id);
    templates.delete(template);
    return ResponseEntity.noContent().build();
  }

  // ---------------------------------------------------------------------------
  // Blocks
  // ---------------------------------------------------------------------------

  @PostMapping("/{id}/blocks")
  public ResponseEntity<TemplateBlockResponse> addBlock(
      @AuthenticationPrincipal User user,
      @PathVariable String id,
      @RequestParam(required = false) Integer order,
      @RequestParam double startSec,
      @RequestParam(required = false) Double endSec,
      @RequestParam(required = false) Integer track,
      @RequestParam(required = false) Integer duration,
      @RequestParam(required = false) Double cropStart,
      @RequestParam(required = false) Double cropEnd,
      @RequestParam(required = false) String prompt,
      @RequestParam(required = false) String model,
      @RequestParam(required = false) String resolution,
      @RequestParam(required = false) String aspectRatio,
      @RequestParam(required = false) Boolean faceSwapStart,
      @RequestParam(required = false) Boolean faceSwapEnd,
      @RequestParam(required = false) Integer avatarSlot,
      @RequestParam(required = false) String swapContext,
      @RequestParam(required = false) String swapModel,
      @RequestParam(required = false) Boolean lipsync,
      @RequestParam(required = false) MultipartFile startImage,
      @RequestParam(required = false) MultipartFile endImage,
      @RequestParam(required = false) MultipartFile sourceVideo) {
    Template template = ownedTemplate(user, id);

    String sourceVideoKey = null;
    Integer uploadedDuration = null;
    if (sourceVideo != null && !sourceVideo.isEmpty()) {
      byte[] data = readBytes(sourceVideo);
      try {
        uploadedDuration = Math.max(1, (int) Math.round(ffmpeg.probeMediaDuration(data)));
      } catch (Exception e) {
        throw new BadRequestException(e.getMessage() != null ? e.getMessage() : "Invalid video upload");
      }
      String origExt = originalExtension(sourceVideo.getOriginalFilename(), "mp4");
      sourceVideoKey = storage.uploadBuffer(data, sourceVideo.getContentType(), "templates/uploads", origExt);
    } else if (prompt == null || prompt.isBlank() || model == null || model.isBlank()) {
      throw new BadRequestException("Prompt and model are required for generated clips.");
    }

    String startImageKey = uploadIfPresent(startImage, "templates");
    String endImageKey = uploadIfPresent(endImage, "templates");

    long count = blocks.countByTemplateId(template.getId());
    int slot = Math.min(avatarSlot != null ? avatarSlot : 0, Math.max(0, template.getAvatarSlots() - 1));
    int effectiveDuration =
        uploadedDuration != null
            ? uploadedDuration
            : duration != null
                ? duration
                : endSec != null ? Math.max(1, (int) Math.round(endSec - startSec)) : 4;
    double[] crop = clampCrop(cropStart != null ? cropStart : 0, cropEnd != null ? cropEnd : effectiveDuration, effectiveDuration);

    TemplateBlock block = new TemplateBlock();
    block.setTemplate(template);
    block.setOrder(order != null ? order : (int) count);
    block.setStartSec(startSec);
    block.setEndSec(startSec + (crop[1] - crop[0]));
    block.setTrack(track != null ? track : 0);
    block.setDuration(effectiveDuration);
    block.setCropStart(crop[0]);
    block.setCropEnd(crop[1]);
    block.setPrompt(prompt != null ? prompt : "");
    block.setModel(model != null ? model : "");
    block.setResolution(resolution);
    block.setAspectRatio(aspectRatio);
    block.setFaceSwapStart(faceSwapStart != null && faceSwapStart);
    block.setFaceSwapEnd(faceSwapEnd != null && faceSwapEnd);
    block.setAvatarSlot(slot);
    block.setSwapContext(swapContext);
    block.setSwapModel(swapModel != null && !swapModel.isBlank() ? swapModel : null);
    block.setLipsync(lipsync != null && lipsync);
    block.setStartImageKey(startImageKey);
    block.setEndImageKey(endImageKey);
    block.setSourceVideoKey(sourceVideoKey);
    block = blocks.save(block);

    return ResponseEntity.status(201).body(TemplateBlockResponse.of(block, storage));
  }

  @PatchMapping("/{id}/blocks/{blockId}")
  public TemplateBlockResponse updateBlock(
      @AuthenticationPrincipal User user,
      @PathVariable String id,
      @PathVariable String blockId,
      @RequestParam(required = false) Integer order,
      @RequestParam(required = false) Double startSec,
      @RequestParam(required = false) Integer track,
      @RequestParam(required = false) Integer duration,
      @RequestParam(required = false) Double cropStart,
      @RequestParam(required = false) Double cropEnd,
      @RequestParam(required = false) String prompt,
      @RequestParam(required = false) String model,
      @RequestParam(required = false) String resolution,
      @RequestParam(required = false) String aspectRatio,
      @RequestParam(required = false) Boolean faceSwapStart,
      @RequestParam(required = false) Boolean faceSwapEnd,
      @RequestParam(required = false) Integer avatarSlot,
      @RequestParam(required = false) String swapContext,
      @RequestParam(required = false) String swapModel,
      @RequestParam(required = false) Boolean lipsync,
      @RequestParam(required = false) MultipartFile startImage,
      @RequestParam(required = false) MultipartFile endImage,
      @RequestParam(required = false) MultipartFile sourceVideo) {
    Template template = ownedTemplate(user, id);
    TemplateBlock existing = blocks.findByIdAndTemplateId(blockId, template.getId()).orElseThrow(() -> new NotFoundException("Block not found"));

    String startImageKey = uploadIfPresent(startImage, "templates");
    String endImageKey = uploadIfPresent(endImage, "templates");

    String sourceVideoKey = null;
    Integer uploadedDuration = null;
    if (sourceVideo != null && !sourceVideo.isEmpty()) {
      byte[] data = readBytes(sourceVideo);
      try {
        uploadedDuration = Math.max(1, (int) Math.round(ffmpeg.probeMediaDuration(data)));
      } catch (Exception e) {
        throw new BadRequestException(e.getMessage() != null ? e.getMessage() : "Invalid video upload");
      }
      String origExt = originalExtension(sourceVideo.getOriginalFilename(), "mp4");
      sourceVideoKey = storage.uploadBuffer(data, sourceVideo.getContentType(), "templates/uploads", origExt);
    }

    Integer slot = avatarSlot == null ? null : Math.min(avatarSlot, Math.max(0, template.getAvatarSlots() - 1));

    double effectiveStartSec = startSec != null ? startSec : existing.getStartSec();
    int effectiveDuration =
        uploadedDuration != null
            ? uploadedDuration
            : duration != null ? duration : existing.getDuration() != null ? existing.getDuration() : Math.max(1, (int) Math.round(existing.getEndSec() - existing.getStartSec()));
    double[] crop =
        clampCrop(
            uploadedDuration != null ? 0 : (cropStart != null ? cropStart : existing.getCropStart()),
            uploadedDuration != null ? effectiveDuration : (cropEnd != null ? cropEnd : (existing.getCropEnd() != null ? existing.getCropEnd() : effectiveDuration)),
            effectiveDuration);

    // A cached swap preview is invalidated when its base frame is replaced, the avatar slot
    // changes, or the swap model changes.
    String newSwapModel = swapModel != null ? (swapModel.isBlank() ? null : swapModel) : null;
    boolean swapModelProvided = swapModel != null;
    boolean avatarChanged = slot != null && !slot.equals(existing.getAvatarSlot());
    boolean swapModelChanged = swapModelProvided && !java.util.Objects.equals(newSwapModel, existing.getSwapModel());
    boolean clearSwappedStart = startImageKey != null || avatarChanged || swapModelChanged;
    boolean clearSwappedEnd = endImageKey != null || avatarChanged || swapModelChanged;

    if (prompt != null) existing.setPrompt(prompt);
    if (model != null) existing.setModel(model);
    if (duration != null || uploadedDuration != null) existing.setDuration(effectiveDuration);
    if (resolution != null) existing.setResolution(resolution);
    if (aspectRatio != null) existing.setAspectRatio(aspectRatio);
    if (faceSwapStart != null) existing.setFaceSwapStart(faceSwapStart);
    if (faceSwapEnd != null) existing.setFaceSwapEnd(faceSwapEnd);
    if (slot != null) existing.setAvatarSlot(slot);
    if (swapContext != null) existing.setSwapContext(swapContext);
    if (swapModelProvided) existing.setSwapModel(newSwapModel);
    if (lipsync != null) existing.setLipsync(lipsync);
    if (startImageKey != null) existing.setStartImageKey(startImageKey);
    if (endImageKey != null) existing.setEndImageKey(endImageKey);
    if (clearSwappedStart) existing.setSwappedStartKey(null);
    if (clearSwappedEnd) existing.setSwappedEndKey(null);
    if (sourceVideoKey != null) existing.setSourceVideoKey(sourceVideoKey);
    if (order != null) existing.setOrder(order);
    existing.setStartSec(effectiveStartSec);
    if (track != null) existing.setTrack(track);
    existing.setCropStart(crop[0]);
    existing.setCropEnd(crop[1]);
    existing.setEndSec(effectiveStartSec + (crop[1] - crop[0]));

    TemplateBlock saved = blocks.save(existing);

    // Propagate shared content (incl. a duration change) to linked siblings, re-clamping each
    // one's own crop window + footprint.
    if (existing.getLinkGroupId() != null) {
      for (TemplateBlock sibling : blocks.findByLinkGroupIdAndIdNot(existing.getLinkGroupId(), existing.getId())) {
        double[] siblingCrop =
            clampCrop(sibling.getCropStart(), sibling.getCropEnd() != null ? sibling.getCropEnd() : effectiveDuration, effectiveDuration);
        if (prompt != null) sibling.setPrompt(prompt);
        if (model != null) sibling.setModel(model);
        if (duration != null || uploadedDuration != null) sibling.setDuration(effectiveDuration);
        if (resolution != null) sibling.setResolution(resolution);
        if (aspectRatio != null) sibling.setAspectRatio(aspectRatio);
        if (faceSwapStart != null) sibling.setFaceSwapStart(faceSwapStart);
        if (faceSwapEnd != null) sibling.setFaceSwapEnd(faceSwapEnd);
        if (slot != null) sibling.setAvatarSlot(slot);
        if (swapContext != null) sibling.setSwapContext(swapContext);
        if (swapModelProvided) sibling.setSwapModel(newSwapModel);
        if (lipsync != null) sibling.setLipsync(lipsync);
        if (startImageKey != null) sibling.setStartImageKey(startImageKey);
        if (endImageKey != null) sibling.setEndImageKey(endImageKey);
        if (clearSwappedStart) sibling.setSwappedStartKey(null);
        if (clearSwappedEnd) sibling.setSwappedEndKey(null);
        if (sourceVideoKey != null) sibling.setSourceVideoKey(sourceVideoKey);
        sibling.setCropStart(siblingCrop[0]);
        sibling.setCropEnd(siblingCrop[1]);
        sibling.setEndSec(sibling.getStartSec() + (siblingCrop[1] - siblingCrop[0]));
        blocks.save(sibling);
      }
    }

    return TemplateBlockResponse.of(saved, storage);
  }

  @DeleteMapping("/{id}/blocks/{blockId}")
  public ResponseEntity<Void> deleteBlock(@AuthenticationPrincipal User user, @PathVariable String id, @PathVariable String blockId) {
    Template template = ownedTemplate(user, id);
    TemplateBlock existing = blocks.findByIdAndTemplateId(blockId, template.getId()).orElseThrow(() -> new NotFoundException("Block not found"));
    blocks.delete(existing);
    return ResponseEntity.noContent().build();
  }

  public record CopyResponse(TemplateBlockResponse block, TemplateBlockResponse source) {}

  /** Clone a block's content into a new, linked block at a new position. */
  @PostMapping("/{id}/blocks/{blockId}/copy")
  public ResponseEntity<CopyResponse> copyBlock(
      @AuthenticationPrincipal User user,
      @PathVariable String id,
      @PathVariable String blockId,
      @RequestParam double startSec,
      @RequestParam(required = false) Integer track) {
    Template template = ownedTemplate(user, id);
    TemplateBlock source = blocks.findByIdAndTemplateId(blockId, template.getId()).orElseThrow(() -> new NotFoundException("Block not found"));

    String linkGroupId = source.getLinkGroupId() != null ? source.getLinkGroupId() : UUID.randomUUID().toString();
    if (source.getLinkGroupId() == null) {
      source.setLinkGroupId(linkGroupId);
      source = blocks.save(source);
    }

    long count = blocks.countByTemplateId(template.getId());
    double footprint = (source.getCropEnd() != null ? source.getCropEnd() : (source.getDuration() != null ? source.getDuration() : 4)) - source.getCropStart();

    TemplateBlock copy = new TemplateBlock();
    copy.setTemplate(template);
    copy.setOrder((int) count);
    copy.setStartSec(startSec);
    copy.setEndSec(startSec + footprint);
    copy.setTrack(track != null ? track : source.getTrack());
    copy.setDuration(source.getDuration());
    copy.setCropStart(source.getCropStart());
    copy.setCropEnd(source.getCropEnd());
    copy.setLinkGroupId(linkGroupId);
    copy.setPrompt(source.getPrompt());
    copy.setModel(source.getModel());
    copy.setResolution(source.getResolution());
    copy.setAspectRatio(source.getAspectRatio());
    copy.setFaceSwapStart(source.isFaceSwapStart());
    copy.setFaceSwapEnd(source.isFaceSwapEnd());
    copy.setAvatarSlot(source.getAvatarSlot());
    copy.setSwapContext(source.getSwapContext());
    copy.setSwapModel(source.getSwapModel());
    copy.setLipsync(source.isLipsync());
    copy.setStartImageKey(source.getStartImageKey());
    copy.setEndImageKey(source.getEndImageKey());
    copy.setSwappedStartKey(source.getSwappedStartKey());
    copy.setSwappedEndKey(source.getSwappedEndKey());
    copy.setVideoKey(source.getVideoKey());
    copy.setSourceVideoKey(source.getSourceVideoKey());
    copy = blocks.save(copy);

    return ResponseEntity.status(201).body(new CopyResponse(TemplateBlockResponse.of(copy, storage), TemplateBlockResponse.of(source, storage)));
  }

  /** Generate just this block's clip so the admin can preview it on the timeline. */
  @PostMapping("/{id}/blocks/{blockId}/bake")
  public ResponseEntity<?> bakeBlock(@AuthenticationPrincipal User user, @PathVariable String id, @PathVariable String blockId) {
    adminService.requireAdmin(user);
    Template template =
        (isSuperAdmin(user) ? templates.findById(id) : templates.findByIdAndCreatorId(id, user.getId()))
            .orElseThrow(() -> new NotFoundException("Not found"));
    TemplateBlock block = blocks.findByIdAndTemplateId(blockId, template.getId()).orElseThrow(() -> new NotFoundException("Block not found"));
    if (block.getSourceVideoKey() != null) {
      throw new BadRequestException("This block uses an uploaded video — there's nothing to bake.");
    }

    byte[] face = resolveBlockFace(template.getAvatarIds(), block.getAvatarSlot(), template.getCreator().getId());

    String lipsyncAudioUrl = null;
    if (block.isLipsync() && OpenRouterClient.supportsAudioLipsync(block.getModel())) {
      List<RenderAudioClipSpec> audioSpecs =
          audioClips.findByTemplateIdOrderByOrderAsc(template.getId()).stream().map(RenderAudioClipSpec::from).toList();
      lipsyncAudioUrl = renderEngine.buildBlockLipsyncAudio(audioSpecs, block.getStartSec(), block.getEndSec());
    }

    try {
      var clip =
          renderEngine.renderBlockClip(
              RenderBlockSpec.from(block), face, new TemplateRenderEngine.RenderBlockClipOptions(true, lipsyncAudioUrl, null, null, null, null));
      String videoKey = storage.uploadBuffer(clip.buffer(), clip.contentType(), "templates/blocks", "mp4");
      block.setVideoKey(videoKey);
      TemplateBlock updated = blocks.save(block);

      if (block.getLinkGroupId() != null) {
        for (TemplateBlock sibling : blocks.findByLinkGroupIdAndIdNot(block.getLinkGroupId(), block.getId())) {
          sibling.setVideoKey(videoKey);
          blocks.save(sibling);
        }
      }
      return ResponseEntity.ok(TemplateBlockResponse.of(updated, storage));
    } catch (Exception e) {
      String message = e.getMessage() != null ? e.getMessage() : "Failed to bake block";
      return ResponseEntity.status(502).body(Map.of("error", message));
    }
  }

  /** Run only the face swap on the block's start/end frame(s) and cache the result for review. */
  @PostMapping("/{id}/blocks/{blockId}/swap")
  public ResponseEntity<?> swapBlock(@AuthenticationPrincipal User user, @PathVariable String id, @PathVariable String blockId) {
    adminService.requireAdmin(user);
    Template template =
        (isSuperAdmin(user) ? templates.findById(id) : templates.findByIdAndCreatorId(id, user.getId()))
            .orElseThrow(() -> new NotFoundException("Not found"));
    TemplateBlock block = blocks.findByIdAndTemplateId(blockId, template.getId()).orElseThrow(() -> new NotFoundException("Block not found"));

    boolean wantStart = block.isFaceSwapStart() && block.getStartImageKey() != null;
    boolean wantEnd = block.isFaceSwapEnd() && block.getEndImageKey() != null;
    if (!wantStart && !wantEnd) {
      throw new BadRequestException("Enable a face-swap toggle and set the matching start/end frame first.");
    }

    byte[] face = resolveBlockFace(template.getAvatarIds(), block.getAvatarSlot(), template.getCreator().getId());
    if (face == null) {
      throw new BadRequestException("This block's avatar slot has no avatar assigned.");
    }

    try {
      String swappedStartKey = wantStart ? swapSide(face, block, block.getStartImageKey()) : null;
      String swappedEndKey = wantEnd ? swapSide(face, block, block.getEndImageKey()) : null;
      if (swappedStartKey != null) block.setSwappedStartKey(swappedStartKey);
      if (swappedEndKey != null) block.setSwappedEndKey(swappedEndKey);
      TemplateBlock updated = blocks.save(block);

      if (block.getLinkGroupId() != null) {
        for (TemplateBlock sibling : blocks.findByLinkGroupIdAndIdNot(block.getLinkGroupId(), block.getId())) {
          if (swappedStartKey != null) sibling.setSwappedStartKey(swappedStartKey);
          if (swappedEndKey != null) sibling.setSwappedEndKey(swappedEndKey);
          blocks.save(sibling);
        }
      }
      return ResponseEntity.ok(TemplateBlockResponse.of(updated, storage));
    } catch (Exception e) {
      String message = e.getMessage() != null ? e.getMessage() : "Failed to generate face swap";
      return ResponseEntity.status(502).body(Map.of("error", message));
    }
  }

  private String swapSide(byte[] face, TemplateBlock block, String frameKey) {
    byte[] base = storage.downloadObject(frameKey);
    String mime = sniffImageMime(base);
    var swapped =
        renderEngine.applyFaceSwap(
            face, base, mime, new TemplateRenderEngine.FaceSwapOptions(block.getSwapModel(), block.getSwapContext(), block.getAspectRatio()));
    return storage.uploadBuffer(swapped.data(), swapped.mime(), "templates/swaps", MediaUtils.extFromMime(swapped.mime()));
  }

  private static String sniffImageMime(byte[] b) {
    if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
      return "image/jpeg";
    }
    if (b.length >= 12 && new String(b, 8, 4).equals("WEBP")) {
      return "image/webp";
    }
    return "image/png";
  }

  /** Capture a still frame from a source clip and set it as this block's start/end frame. */
  @PostMapping("/{id}/blocks/{blockId}/frame")
  public ResponseEntity<?> captureFrame(
      @AuthenticationPrincipal User user,
      @PathVariable String id,
      @PathVariable String blockId,
      @RequestParam String sourceBlockId,
      @RequestParam double atSec,
      @RequestParam String slot) {
    Template template = ownedTemplate(user, id);
    TemplateBlock target = blocks.findByIdAndTemplateId(blockId, template.getId()).orElseThrow(() -> new NotFoundException("Block not found"));
    if (!"start".equals(slot) && !"end".equals(slot)) {
      throw new BadRequestException("slot must be \"start\" or \"end\"");
    }
    TemplateBlock source = blocks.findByIdAndTemplateId(sourceBlockId, template.getId()).orElse(null);
    String sourceKey = source != null ? (source.getSourceVideoKey() != null ? source.getSourceVideoKey() : source.getVideoKey()) : null;
    if (source == null || sourceKey == null) {
      throw new BadRequestException("The previewed clip has no video to grab a frame from.");
    }

    try {
      byte[] clip = storage.downloadObject(sourceKey);
      byte[] frame = ffmpeg.generateThumbnail(clip, atSec);
      String frameKey = storage.uploadBuffer(frame, "image/jpeg", "templates/frames", "jpg");
      boolean isStart = "start".equals(slot);
      if (isStart) {
        target.setStartImageKey(frameKey);
        target.setSwappedStartKey(null);
      } else {
        target.setEndImageKey(frameKey);
        target.setSwappedEndKey(null);
      }
      TemplateBlock updated = blocks.save(target);

      if (target.getLinkGroupId() != null) {
        for (TemplateBlock sibling : blocks.findByLinkGroupIdAndIdNot(target.getLinkGroupId(), target.getId())) {
          if (isStart) {
            sibling.setStartImageKey(frameKey);
            sibling.setSwappedStartKey(null);
          } else {
            sibling.setEndImageKey(frameKey);
            sibling.setSwappedEndKey(null);
          }
          blocks.save(sibling);
        }
      }
      return ResponseEntity.ok(TemplateBlockResponse.of(updated, storage));
    } catch (Exception e) {
      String message = e.getMessage() != null ? e.getMessage() : "Failed to capture frame";
      return ResponseEntity.status(502).body(Map.of("error", message));
    }
  }

  // ---------------------------------------------------------------------------
  // Audio clips
  // ---------------------------------------------------------------------------

  @PostMapping("/{id}/audio")
  public ResponseEntity<?> addAudioClip(
      @AuthenticationPrincipal User user,
      @PathVariable String id,
      @RequestParam MultipartFile audio,
      @RequestParam(required = false) Integer order,
      @RequestParam(required = false) Double startSec,
      @RequestParam(required = false) Integer track,
      @RequestParam(required = false) Double cropStart,
      @RequestParam(required = false) Double cropEnd) {
    Template template = ownedTemplate(user, id);
    if (audio == null || audio.isEmpty()) {
      throw new BadRequestException("An audio file is required.");
    }
    byte[] data = readBytes(audio);
    double duration;
    try {
      duration = ffmpeg.probeMediaDuration(data);
    } catch (Exception e) {
      throw new BadRequestException(e.getMessage() != null ? e.getMessage() : "Invalid audio upload");
    }
    String audioKey = storage.uploadBuffer(data, audio.getContentType(), "templates/audio", originalExtension(audio.getOriginalFilename(), "mp3"));

    double effectiveStart = startSec != null ? startSec : 0;
    double[] crop = clampCrop(cropStart != null ? cropStart : 0, cropEnd != null ? cropEnd : duration, duration);
    long count = audioClips.countByTemplateId(template.getId());

    TemplateAudioClip clip = new TemplateAudioClip();
    clip.setTemplate(template);
    clip.setOrder(order != null ? order : (int) count);
    clip.setStartSec(effectiveStart);
    clip.setEndSec(effectiveStart + (crop[1] - crop[0]));
    clip.setTrack(track != null ? track : 0);
    clip.setAudioKey(audioKey);
    clip.setName(audio.getOriginalFilename());
    clip.setDuration(duration);
    clip.setCropStart(crop[0]);
    clip.setCropEnd(crop[1]);
    clip = audioClips.save(clip);

    return ResponseEntity.status(201).body(TemplateAudioClipResponse.of(clip, storage));
  }

  @PatchMapping("/{id}/audio/{clipId}")
  public TemplateAudioClipResponse updateAudioClip(
      @AuthenticationPrincipal User user,
      @PathVariable String id,
      @PathVariable String clipId,
      @RequestParam(required = false) MultipartFile audio,
      @RequestParam(required = false) Integer order,
      @RequestParam(required = false) Double startSec,
      @RequestParam(required = false) Integer track,
      @RequestParam(required = false) Double cropStart,
      @RequestParam(required = false) Double cropEnd) {
    Template template = ownedTemplate(user, id);
    TemplateAudioClip existing =
        audioClips.findByIdAndTemplateId(clipId, template.getId()).orElseThrow(() -> new NotFoundException("Audio clip not found"));

    String audioKey = null;
    Double newDuration = null;
    if (audio != null && !audio.isEmpty()) {
      byte[] data = readBytes(audio);
      try {
        newDuration = ffmpeg.probeMediaDuration(data);
      } catch (Exception e) {
        throw new BadRequestException(e.getMessage() != null ? e.getMessage() : "Invalid audio upload");
      }
      audioKey = storage.uploadBuffer(data, audio.getContentType(), "templates/audio", originalExtension(audio.getOriginalFilename(), "mp3"));
    }

    double duration = newDuration != null ? newDuration : existing.getDuration();
    double effectiveStart = startSec != null ? startSec : existing.getStartSec();
    double[] crop =
        clampCrop(
            newDuration != null ? 0 : (cropStart != null ? cropStart : existing.getCropStart()),
            newDuration != null ? duration : (cropEnd != null ? cropEnd : (existing.getCropEnd() != null ? existing.getCropEnd() : duration)),
            duration);

    if (order != null) existing.setOrder(order);
    existing.setStartSec(effectiveStart);
    if (track != null) existing.setTrack(track);
    existing.setCropStart(crop[0]);
    existing.setCropEnd(crop[1]);
    existing.setEndSec(effectiveStart + (crop[1] - crop[0]));
    if (audioKey != null) {
      existing.setAudioKey(audioKey);
      existing.setDuration(duration);
      existing.setName(audio.getOriginalFilename());
    }

    TemplateAudioClip saved = audioClips.save(existing);
    return TemplateAudioClipResponse.of(saved, storage);
  }

  @DeleteMapping("/{id}/audio/{clipId}")
  public ResponseEntity<Void> deleteAudioClip(@AuthenticationPrincipal User user, @PathVariable String id, @PathVariable String clipId) {
    Template template = ownedTemplate(user, id);
    TemplateAudioClip existing =
        audioClips.findByIdAndTemplateId(clipId, template.getId()).orElseThrow(() -> new NotFoundException("Audio clip not found"));
    audioClips.delete(existing);
    return ResponseEntity.noContent().build();
  }

  // ---------------------------------------------------------------------------
  // Export (render with the template's assigned avatars + publish)
  // ---------------------------------------------------------------------------

  @PostMapping("/{id}/export")
  public ResponseEntity<?> export(@AuthenticationPrincipal User user, @PathVariable String id) {
    adminService.requireAdmin(user);
    Template template =
        (isSuperAdmin(user) ? templates.findById(id) : templates.findByIdAndCreatorId(id, user.getId()))
            .orElseThrow(() -> new NotFoundException("Not found"));
    List<TemplateBlock> templateBlocks = blocks.findByTemplateIdOrderByOrderAsc(template.getId());
    if (templateBlocks.isEmpty()) {
      throw new BadRequestException("Add at least one video block before exporting.");
    }
    if (template.getAvatarIds().isEmpty()) {
      throw new BadRequestException("Assign avatars to this template before exporting.");
    }

    // The template's avatars belong to its creator (a superadmin may be exporting another
    // admin's template), so resolve + attribute the render to that user.
    List<Avatar> ownerAvatars =
        avatars.findByIdIn(template.getAvatarIds()).stream()
            .filter(a -> a.getUser().getId().equals(template.getCreator().getId()))
            .toList();
    if (ownerAvatars.size() != template.getAvatarIds().size()) {
      throw new BadRequestException("One or more of the template's avatars were not found.");
    }
    List<Avatar> orderedAvatars =
        template.getAvatarIds().stream().map(aid -> ownerAvatars.stream().filter(a -> a.getId().equals(aid)).findFirst().orElseThrow()).toList();

    TemplateRender render = new TemplateRender();
    render.setTemplate(template);
    render.setUser(template.getCreator());
    render.setAvatarIds(template.getAvatarIds());
    render.setAvatars(new HashSet<>(orderedAvatars));
    render.setStatus(GenerationStatus.IN_PROGRESS);
    render = renders.save(render);
    String renderId = render.getId();

    List<RenderBlockSpec> specs = templateBlocks.stream().map(RenderBlockSpec::from).toList();
    List<RenderAvatarSpec> avatarSpecs = orderedAvatars.stream().map(a -> new RenderAvatarSpec(a.getFaceKey())).toList();
    List<RenderAudioClipSpec> audioSpecs =
        audioClips.findByTemplateIdOrderByOrderAsc(template.getId()).stream().map(RenderAudioClipSpec::from).toList();

    try {
      TemplateRenderRunner.RunResult result =
          renderRunner.runAndStoreRender(
              TemplateRenderRunner.RunParams.builder()
                  .renderId(renderId)
                  .blocks(specs)
                  .orderedAvatars(avatarSpecs)
                  .audioClips(audioSpecs)
                  .aiThumbnail(true) // export -> AI-generate the cover thumbnail (avatar as reference)
                  .thumbnailPrompt(template.getThumbnailPrompt())
                  .build());

      template.setPublished(true);
      template.setPreviewVideoKey(result.videoKey());
      template.setThumbnailKey(result.thumbnailKey());
      Template updated = templates.save(template);
      List<TemplateBlockResponse> blockRows =
          blocks.findByTemplateIdOrderByOrderAsc(id).stream().map(b -> TemplateBlockResponse.of(b, storage)).toList();
      List<TemplateAudioClipResponse> audioRows =
          audioClips.findByTemplateIdOrderByOrderAsc(id).stream().map(c -> TemplateAudioClipResponse.of(c, storage)).toList();
      return ResponseEntity.ok(TemplateResponse.of(updated, storage, blockRows, audioRows, blockRows.size()));
    } catch (Exception e) {
      String message = e.getMessage() != null ? e.getMessage() : "Template export failed";
      TemplateRender failed = renders.findById(renderId).orElse(null);
      return ResponseEntity.status(502)
          .body(Map.of("error", message, "render", failed != null ? TemplateRenderResponse.of(failed, storage) : null));
    }
  }

  // ---------------------------------------------------------------------------
  // Small helpers
  // ---------------------------------------------------------------------------

  private static byte[] readBytes(MultipartFile file) {
    try {
      return file.getBytes();
    } catch (IOException e) {
      throw new BadRequestException("Failed to read uploaded file \"" + file.getOriginalFilename() + "\"");
    }
  }

  private static String originalExtension(String filename, String fallback) {
    if (filename == null || !filename.contains(".")) {
      return fallback;
    }
    String ext = filename.substring(filename.lastIndexOf('.') + 1);
    return ext.isBlank() ? fallback : ext;
  }
}
