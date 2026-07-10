package com.pixovid.backend.generation;

import com.pixovid.backend.common.BadRequestException;
import com.pixovid.backend.common.MediaUtils;
import com.pixovid.backend.common.NotFoundException;
import com.pixovid.backend.credits.CreditsService;
import com.pixovid.backend.credits.GenerationAction;
import com.pixovid.backend.credits.InsufficientCreditsException;
import com.pixovid.backend.generation.dto.VideoResponse;
import com.pixovid.backend.openrouter.GenerateVideoParams;
import com.pixovid.backend.openrouter.GeneratedVideo;
import com.pixovid.backend.openrouter.ImageRef;
import com.pixovid.backend.openrouter.OpenRouterClient;
import com.pixovid.backend.storage.StorageService;
import com.pixovid.backend.user.User;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Port of apps/backend/src/routes/videos.ts. All routes require auth (enforced by SecurityConfig). */
@RestController
@RequestMapping("/api/videos")
public class VideosController {

  private final VideoRepository videos;
  private final StorageService storage;
  private final OpenRouterClient openRouter;
  private final CreditsService creditsService;

  public VideosController(
      VideoRepository videos, StorageService storage, OpenRouterClient openRouter, CreditsService creditsService) {
    this.videos = videos;
    this.storage = storage;
    this.openRouter = openRouter;
    this.creditsService = creditsService;
  }

  @GetMapping
  public List<VideoResponse> list(@AuthenticationPrincipal User user) {
    return videos.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
        .map(v -> VideoResponse.of(v, storage))
        .toList();
  }

  @GetMapping("/{id}")
  public VideoResponse get(@AuthenticationPrincipal User user, @PathVariable String id) {
    Video video = videos.findByIdAndUserId(id, user.getId()).orElseThrow(() -> new NotFoundException("Not found"));
    return VideoResponse.of(video, storage);
  }

  @PostMapping
  public ResponseEntity<?> create(
      @AuthenticationPrincipal User user,
      @RequestParam String prompt,
      @RequestParam String model,
      @RequestParam(required = false) Integer duration,
      @RequestParam(required = false) String resolution,
      @RequestParam(required = false) String aspectRatio,
      @RequestParam(required = false) Boolean generateAudio,
      @RequestParam(required = false) MultipartFile startFrame,
      @RequestParam(required = false) MultipartFile endFrame,
      @RequestParam(required = false) List<MultipartFile> referenceFrames) {
    if (prompt == null || prompt.isBlank()) {
      throw new BadRequestException("Prompt is required");
    }
    if (model == null || model.isBlank()) {
      throw new BadRequestException("Model is required");
    }

    // Credits: fixed price per video. Reject up front if unaffordable (avoids wasting uploads).
    int cost = creditsService.actionCost(GenerationAction.VIDEO);
    if (creditsService.getBalance(user.getId()) < cost) {
      return ResponseEntity.status(402)
          .body(Map.of("error", "Not enough credits. This video costs " + cost + " credits."));
    }

    List<MultipartFile> refs = referenceFrames != null ? referenceFrames : List.of();
    boolean hasStartFrame = startFrame != null && !startFrame.isEmpty();
    boolean hasEndFrame = endFrame != null && !endFrame.isEmpty();

    // 1. Persist uploaded input images to the object store.
    String startFrameKey = hasStartFrame ? uploadInput(startFrame) : null;
    String endFrameKey = hasEndFrame ? uploadInput(endFrame) : null;
    List<String> referenceFrameKeys = refs.stream().map(this::uploadInput).toList();

    // 2. Create the DB record up front.
    Video video = new Video();
    video.setUser(user);
    video.setPrompt(prompt);
    video.setModel(model);
    video.setDuration(duration);
    video.setResolution(resolution);
    video.setAspectRatio(aspectRatio);
    video.setGenerateAudio(generateAudio);
    video.setStartFrameKey(startFrameKey);
    video.setEndFrameKey(endFrameKey);
    video.setReferenceFrameKeys(referenceFrameKeys);
    video.setStatus(GenerationStatus.IN_PROGRESS);
    video = videos.save(video);

    // 2b. Charge credits now that we have a row to reference. A race could make this fail even
    // after the up-front check; if so, mark the row failed + 402.
    try {
      creditsService.spendCredits(
          user.getId(), cost, CreditsService.LedgerRef.of("video", video.getId(), "Video generation"));
    } catch (InsufficientCreditsException e) {
      video.setStatus(GenerationStatus.FAILED);
      video.setError("Not enough credits.");
      videos.save(video);
      return ResponseEntity.status(402)
          .body(Map.of("error", "Not enough credits. This video costs " + cost + " credits."));
    }

    // 3. Generate synchronously via OpenRouter, then store the output.
    try {
      GeneratedVideo generated =
          openRouter.generateVideo(
              new GenerateVideoParams(
                  model,
                  prompt,
                  duration,
                  resolution,
                  aspectRatio,
                  generateAudio,
                  hasStartFrame ? new ImageRef(MediaUtils.toDataUrl(startFrame)) : null,
                  hasEndFrame ? new ImageRef(MediaUtils.toDataUrl(endFrame)) : null,
                  refs.stream().map(f -> new ImageRef(MediaUtils.toDataUrl(f))).toList(),
                  null));

      String videoKey = storage.uploadBuffer(generated.data(), generated.contentType(), "videos", "mp4");

      video.setStatus(GenerationStatus.COMPLETED);
      video.setVideoKey(videoKey);
      video.setProviderJobId(generated.providerJobId());
      video.setCost(generated.cost());
      video = videos.save(video);
      return ResponseEntity.status(201).body(VideoResponse.of(video, storage));
    } catch (Exception e) {
      String message = e.getMessage() != null ? e.getMessage() : "Video generation failed";
      // Refund the credits we charged — the user got nothing.
      creditsService.refundCredits(user.getId(), cost, "video", video.getId(), "Refund: video generation failed");
      video.setStatus(GenerationStatus.FAILED);
      video.setError(message);
      video = videos.save(video);
      return ResponseEntity.status(502).body(Map.of("error", message, "video", VideoResponse.of(video, storage)));
    }
  }

  private String uploadInput(MultipartFile file) {
    try {
      return storage.uploadBuffer(
          file.getBytes(), file.getContentType(), "inputs", MediaUtils.extFromMime(file.getContentType()));
    } catch (IOException e) {
      throw new BadRequestException("Failed to read uploaded file \"" + file.getOriginalFilename() + "\"");
    }
  }
}
