package com.pixovid.backend.avatar;

import com.pixovid.backend.avatar.dto.AvatarResponse;
import com.pixovid.backend.common.BadRequestException;
import com.pixovid.backend.common.MediaUtils;
import com.pixovid.backend.common.NotFoundException;
import com.pixovid.backend.generation.GenerationStatus;
import com.pixovid.backend.storage.StorageService;
import com.pixovid.backend.user.User;
import java.io.IOException;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Port of apps/backend/src/routes/avatars.ts. All routes require auth (enforced by SecurityConfig). */
@RestController
@RequestMapping("/api/avatars")
public class AvatarsController {

  private final AvatarRepository avatars;
  private final StorageService storage;

  public AvatarsController(AvatarRepository avatars, StorageService storage) {
    this.avatars = avatars;
    this.storage = storage;
  }

  @GetMapping
  public List<AvatarResponse> list(@AuthenticationPrincipal User user) {
    return avatars.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
        .map(a -> AvatarResponse.of(a, storage))
        .toList();
  }

  @GetMapping("/{id}")
  public AvatarResponse get(@AuthenticationPrincipal User user, @PathVariable String id) {
    Avatar avatar = avatars.findByIdAndUserId(id, user.getId()).orElseThrow(() -> new NotFoundException("Not found"));
    return AvatarResponse.of(avatar, storage);
  }

  /** Create an avatar from 1-2 photos. The first photo becomes the primary face image used for face swaps + references. */
  @PostMapping
  public ResponseEntity<AvatarResponse> create(
      @AuthenticationPrincipal User user,
      @RequestParam String name,
      @RequestParam(required = false) List<MultipartFile> images) {
    if (name == null || name.isBlank()) {
      throw new BadRequestException("Name is required");
    }
    List<MultipartFile> photos = images != null ? images : List.of();
    if (photos.isEmpty()) {
      throw new BadRequestException("At least one photo is required.");
    }

    List<String> sourceImageKeys =
        photos.stream()
            .map(
                f -> {
                  try {
                    return storage.uploadBuffer(
                        f.getBytes(), f.getContentType(), "avatars", MediaUtils.extFromMime(f.getContentType()));
                  } catch (IOException e) {
                    throw new BadRequestException("Failed to read uploaded file \"" + f.getOriginalFilename() + "\"");
                  }
                })
            .toList();

    Avatar avatar = new Avatar();
    avatar.setUser(user);
    avatar.setName(name);
    avatar.setSourceImageKeys(sourceImageKeys);
    avatar.setFaceKey(sourceImageKeys.get(0));
    avatar.setStatus(GenerationStatus.COMPLETED);
    avatar = avatars.save(avatar);

    return ResponseEntity.status(201).body(AvatarResponse.of(avatar, storage));
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@AuthenticationPrincipal User user, @PathVariable String id) {
    Avatar avatar = avatars.findByIdAndUserId(id, user.getId()).orElseThrow(() -> new NotFoundException("Not found"));
    avatars.delete(avatar);
    return ResponseEntity.noContent().build();
  }
}
