package com.pixovid.backend.generation.dto;

import com.pixovid.backend.generation.GenerationStatus;
import com.pixovid.backend.generation.Video;
import com.pixovid.backend.storage.StorageService;
import java.time.LocalDateTime;
import java.util.List;

/** Serializes a Video row, attaching public URLs for stored objects (mirrors videos.ts's serializeVideo). */
public record VideoResponse(
    String id,
    String userId,
    GenerationStatus status,
    String prompt,
    String model,
    Integer duration,
    String resolution,
    String aspectRatio,
    Boolean generateAudio,
    String startFrameKey,
    String endFrameKey,
    List<String> referenceFrameKeys,
    String videoKey,
    String providerJobId,
    Double cost,
    String error,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    String videoUrl,
    String startFrameUrl,
    String endFrameUrl,
    List<String> referenceFrameUrls) {

  public static VideoResponse of(Video v, StorageService storage) {
    return new VideoResponse(
        v.getId(),
        v.getUser().getId(),
        v.getStatus(),
        v.getPrompt(),
        v.getModel(),
        v.getDuration(),
        v.getResolution(),
        v.getAspectRatio(),
        v.getGenerateAudio(),
        v.getStartFrameKey(),
        v.getEndFrameKey(),
        v.getReferenceFrameKeys(),
        v.getVideoKey(),
        v.getProviderJobId(),
        v.getCost(),
        v.getError(),
        v.getCreatedAt(),
        v.getUpdatedAt(),
        v.getVideoKey() != null ? storage.getPublicUrl(v.getVideoKey()) : null,
        v.getStartFrameKey() != null ? storage.getPublicUrl(v.getStartFrameKey()) : null,
        v.getEndFrameKey() != null ? storage.getPublicUrl(v.getEndFrameKey()) : null,
        v.getReferenceFrameKeys().stream().map(storage::getPublicUrl).toList());
  }
}
