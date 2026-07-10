package com.pixovid.backend.generation;

import com.pixovid.backend.common.BadRequestException;
import com.pixovid.backend.common.MediaUtils;
import com.pixovid.backend.common.NotFoundException;
import com.pixovid.backend.credits.CreditsService;
import com.pixovid.backend.credits.GenerationAction;
import com.pixovid.backend.credits.InsufficientCreditsException;
import com.pixovid.backend.generation.dto.ImageResponse;
import com.pixovid.backend.openrouter.GenerateImageParams;
import com.pixovid.backend.openrouter.GeneratedImage;
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

/** Port of apps/backend/src/routes/images.ts. All routes require auth (enforced by SecurityConfig). */
@RestController
@RequestMapping("/api/images")
public class ImagesController {

  private final ImageRepository images;
  private final StorageService storage;
  private final OpenRouterClient openRouter;
  private final CreditsService creditsService;

  public ImagesController(
      ImageRepository images, StorageService storage, OpenRouterClient openRouter, CreditsService creditsService) {
    this.images = images;
    this.storage = storage;
    this.openRouter = openRouter;
    this.creditsService = creditsService;
  }

  @GetMapping
  public List<ImageResponse> list(@AuthenticationPrincipal User user) {
    return images.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
        .map(i -> ImageResponse.of(i, storage))
        .toList();
  }

  @GetMapping("/{id}")
  public ImageResponse get(@AuthenticationPrincipal User user, @PathVariable String id) {
    Image image = images.findByIdAndUserId(id, user.getId()).orElseThrow(() -> new NotFoundException("Not found"));
    return ImageResponse.of(image, storage);
  }

  @PostMapping
  public ResponseEntity<?> create(
      @AuthenticationPrincipal User user,
      @RequestParam String prompt,
      @RequestParam String model,
      @RequestParam(required = false) String resolution,
      @RequestParam(required = false) String aspectRatio,
      @RequestParam(required = false) List<MultipartFile> referenceImages) {
    if (prompt == null || prompt.isBlank()) {
      throw new BadRequestException("Prompt is required");
    }
    if (model == null || model.isBlank()) {
      throw new BadRequestException("Model is required");
    }

    int cost = creditsService.actionCost(GenerationAction.IMAGE);
    if (creditsService.getBalance(user.getId()) < cost) {
      return ResponseEntity.status(402)
          .body(Map.of("error", "Not enough credits. This image costs " + cost + " credits."));
    }

    List<MultipartFile> refs = referenceImages != null ? referenceImages : List.of();

    // 1. Persist uploaded reference inputs to the object store.
    List<String> referenceImageKeys = refs.stream().map(this::uploadInput).toList();

    // 2. Create the DB record up front.
    Image image = new Image();
    image.setUser(user);
    image.setPrompt(prompt);
    image.setModel(model);
    image.setResolution(resolution);
    image.setAspectRatio(aspectRatio);
    image.setReferenceImageKeys(referenceImageKeys);
    image.setStatus(GenerationStatus.IN_PROGRESS);
    image = images.save(image);

    // 2b. Charge credits now that the row exists (refunded on failure below).
    try {
      creditsService.spendCredits(
          user.getId(), cost, CreditsService.LedgerRef.of("image", image.getId(), "Image generation"));
    } catch (InsufficientCreditsException e) {
      image.setStatus(GenerationStatus.FAILED);
      image.setError("Not enough credits.");
      images.save(image);
      return ResponseEntity.status(402)
          .body(Map.of("error", "Not enough credits. This image costs " + cost + " credits."));
    }

    // 3. Generate synchronously via OpenRouter, then store the output.
    try {
      GeneratedImage generated =
          openRouter.generateImage(
              new GenerateImageParams(
                  model,
                  prompt,
                  resolution,
                  aspectRatio,
                  refs.stream().map(f -> new ImageRef(MediaUtils.toDataUrl(f))).toList()));

      String ext = MediaUtils.extFromMime(generated.contentType());
      String imageKey = storage.uploadBuffer(generated.data(), generated.contentType(), "images", ext);

      image.setStatus(GenerationStatus.COMPLETED);
      image.setImageKey(imageKey);
      image.setCost(generated.cost());
      image = images.save(image);
      return ResponseEntity.status(201).body(ImageResponse.of(image, storage));
    } catch (Exception e) {
      String message = e.getMessage() != null ? e.getMessage() : "Image generation failed";
      creditsService.refundCredits(user.getId(), cost, "image", image.getId(), "Refund: image generation failed");
      image.setStatus(GenerationStatus.FAILED);
      image.setError(message);
      image = images.save(image);
      return ResponseEntity.status(502).body(Map.of("error", message, "image", ImageResponse.of(image, storage)));
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
