package com.pixovid.backend.template.dto;

import com.pixovid.backend.generation.GenerationStatus;
import com.pixovid.backend.storage.StorageService;
import com.pixovid.backend.template.TemplateRender;
import java.time.LocalDateTime;
import java.util.List;

public record TemplateRenderResponse(
    String id,
    String templateId,
    String userId,
    GenerationStatus status,
    List<String> avatarIds,
    String videoKey,
    String thumbnailKey,
    Double cost,
    String error,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    String videoUrl,
    String thumbnailUrl,
    List<TemplateRenderBlockResponse> blocks,
    String templateName) {

  public static TemplateRenderResponse of(TemplateRender r, StorageService storage) {
    return of(r, storage, null, null);
  }

  public static TemplateRenderResponse of(
      TemplateRender r, StorageService storage, List<TemplateRenderBlockResponse> blocks, String templateName) {
    return new TemplateRenderResponse(
        r.getId(),
        r.getTemplate().getId(),
        r.getUser().getId(),
        r.getStatus(),
        r.getAvatarIds(),
        r.getVideoKey(),
        r.getThumbnailKey(),
        r.getCost(),
        r.getError(),
        r.getCreatedAt(),
        r.getUpdatedAt(),
        r.getVideoKey() != null ? storage.getPublicUrl(r.getVideoKey()) : null,
        r.getThumbnailKey() != null ? storage.getPublicUrl(r.getThumbnailKey()) : null,
        blocks,
        templateName);
  }
}
