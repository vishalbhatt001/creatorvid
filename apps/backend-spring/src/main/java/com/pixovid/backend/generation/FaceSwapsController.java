package com.pixovid.backend.generation;

import com.pixovid.backend.common.BadRequestException;
import com.pixovid.backend.common.MediaUtils;
import com.pixovid.backend.common.NotFoundException;
import com.pixovid.backend.facefusion.FaceFusionClient;
import com.pixovid.backend.generation.dto.FaceSwapResponse;
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

/** Port of apps/backend/src/routes/faceswaps.ts. All routes require auth (enforced by SecurityConfig). */
@RestController
@RequestMapping("/api/faceswaps")
public class FaceSwapsController {

  private final FaceSwapRepository faceSwaps;
  private final StorageService storage;
  private final FaceFusionClient faceFusion;

  public FaceSwapsController(FaceSwapRepository faceSwaps, StorageService storage, FaceFusionClient faceFusion) {
    this.faceSwaps = faceSwaps;
    this.storage = storage;
    this.faceFusion = faceFusion;
  }

  @GetMapping
  public List<FaceSwapResponse> list(@AuthenticationPrincipal User user) {
    return faceSwaps.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
        .map(s -> FaceSwapResponse.of(s, storage))
        .toList();
  }

  @GetMapping("/{id}")
  public FaceSwapResponse get(@AuthenticationPrincipal User user, @PathVariable String id) {
    FaceSwap swap =
        faceSwaps.findByIdAndUserId(id, user.getId()).orElseThrow(() -> new NotFoundException("Not found"));
    return FaceSwapResponse.of(swap, storage);
  }

  @PostMapping
  public ResponseEntity<?> create(
      @AuthenticationPrincipal User user,
      @RequestParam(required = false) MultipartFile source,
      @RequestParam(required = false) MultipartFile target) {
    if (source == null || source.isEmpty() || target == null || target.isEmpty()) {
      throw new BadRequestException("Both a base image and a face image are required.");
    }

    // 1. Persist both uploaded inputs to the object store.
    String sourceKey = uploadInput(source);
    String targetKey = uploadInput(target);

    // 2. Create the DB record up front.
    FaceSwap swap = new FaceSwap();
    swap.setUser(user);
    swap.setSourceKey(sourceKey);
    swap.setTargetKey(targetKey);
    swap.setStatus(GenerationStatus.IN_PROGRESS);
    swap = faceSwaps.save(swap);

    // 3. Run the swap synchronously via FaceFusion, then store the output.
    try {
      byte[] sourceBytes = readBytes(source);
      byte[] targetBytes = readBytes(target);
      String sourceExt = MediaUtils.extFromMime(source.getContentType());
      String targetExt = MediaUtils.extFromMime(target.getContentType());

      FaceFusionClient.FaceSwapResult result =
          faceFusion.faceSwap(
              new FaceFusionClient.FaceSwapInput(sourceBytes, source.getContentType(), "source." + sourceExt),
              new FaceFusionClient.FaceSwapInput(targetBytes, target.getContentType(), "target." + targetExt));

      String ext = MediaUtils.extFromMime(result.contentType());
      String outputKey = storage.uploadBuffer(result.data(), result.contentType(), "faceswaps", ext);

      swap.setStatus(GenerationStatus.COMPLETED);
      swap.setOutputKey(outputKey);
      swap = faceSwaps.save(swap);
      return ResponseEntity.status(201).body(FaceSwapResponse.of(swap, storage));
    } catch (Exception e) {
      String message = e.getMessage() != null ? e.getMessage() : "Face swap failed";
      swap.setStatus(GenerationStatus.FAILED);
      swap.setError(message);
      swap = faceSwaps.save(swap);
      return ResponseEntity.status(502)
          .body(Map.of("error", message, "faceSwap", FaceSwapResponse.of(swap, storage)));
    }
  }

  private String uploadInput(MultipartFile file) {
    return storage.uploadBuffer(
        readBytes(file), file.getContentType(), "inputs", MediaUtils.extFromMime(file.getContentType()));
  }

  private byte[] readBytes(MultipartFile file) {
    try {
      return file.getBytes();
    } catch (IOException e) {
      throw new BadRequestException("Failed to read uploaded file \"" + file.getOriginalFilename() + "\"");
    }
  }
}
