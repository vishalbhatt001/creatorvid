package com.pixovid.backend.generation.dto;

import com.pixovid.backend.generation.FaceSwap;
import com.pixovid.backend.generation.GenerationStatus;
import com.pixovid.backend.storage.StorageService;
import java.time.LocalDateTime;

public record FaceSwapResponse(
    String id,
    String userId,
    GenerationStatus status,
    String sourceKey,
    String targetKey,
    String outputKey,
    String error,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    String sourceUrl,
    String targetUrl,
    String outputUrl) {

  public static FaceSwapResponse of(FaceSwap s, StorageService storage) {
    return new FaceSwapResponse(
        s.getId(),
        s.getUser().getId(),
        s.getStatus(),
        s.getSourceKey(),
        s.getTargetKey(),
        s.getOutputKey(),
        s.getError(),
        s.getCreatedAt(),
        s.getUpdatedAt(),
        storage.getPublicUrl(s.getSourceKey()),
        storage.getPublicUrl(s.getTargetKey()),
        s.getOutputKey() != null ? storage.getPublicUrl(s.getOutputKey()) : null);
  }
}
