package com.pixovid.backend.avatar.dto;

import com.pixovid.backend.avatar.Avatar;
import com.pixovid.backend.generation.GenerationStatus;
import com.pixovid.backend.storage.StorageService;
import java.time.LocalDateTime;
import java.util.List;

public record AvatarResponse(
    String id,
    String userId,
    GenerationStatus status,
    String name,
    List<String> sourceImageKeys,
    String faceKey,
    String error,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    String faceUrl,
    List<String> sourceImageUrls) {

  public static AvatarResponse of(Avatar a, StorageService storage) {
    return new AvatarResponse(
        a.getId(),
        a.getUser().getId(),
        a.getStatus(),
        a.getName(),
        a.getSourceImageKeys(),
        a.getFaceKey(),
        a.getError(),
        a.getCreatedAt(),
        a.getUpdatedAt(),
        a.getFaceKey() != null ? storage.getPublicUrl(a.getFaceKey()) : null,
        a.getSourceImageKeys().stream().map(storage::getPublicUrl).toList());
  }
}
