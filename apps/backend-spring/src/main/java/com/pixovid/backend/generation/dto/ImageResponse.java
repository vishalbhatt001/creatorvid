package com.pixovid.backend.generation.dto;

import com.pixovid.backend.generation.GenerationStatus;
import com.pixovid.backend.generation.Image;
import com.pixovid.backend.storage.StorageService;
import java.time.LocalDateTime;
import java.util.List;

public record ImageResponse(
    String id,
    String userId,
    GenerationStatus status,
    String prompt,
    String model,
    String resolution,
    String aspectRatio,
    List<String> referenceImageKeys,
    String imageKey,
    String providerJobId,
    Double cost,
    String error,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    String imageUrl,
    List<String> referenceImageUrls) {

  public static ImageResponse of(Image i, StorageService storage) {
    return new ImageResponse(
        i.getId(),
        i.getUser().getId(),
        i.getStatus(),
        i.getPrompt(),
        i.getModel(),
        i.getResolution(),
        i.getAspectRatio(),
        i.getReferenceImageKeys(),
        i.getImageKey(),
        i.getProviderJobId(),
        i.getCost(),
        i.getError(),
        i.getCreatedAt(),
        i.getUpdatedAt(),
        i.getImageKey() != null ? storage.getPublicUrl(i.getImageKey()) : null,
        i.getReferenceImageKeys().stream().map(storage::getPublicUrl).toList());
  }
}
