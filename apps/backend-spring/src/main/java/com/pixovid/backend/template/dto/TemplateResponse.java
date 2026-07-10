package com.pixovid.backend.template.dto;

import com.pixovid.backend.storage.StorageService;
import com.pixovid.backend.template.Template;
import java.time.LocalDateTime;
import java.util.List;

public record TemplateResponse(
    String id,
    String creatorId,
    String name,
    String description,
    int avatarSlots,
    List<String> avatarIds,
    boolean published,
    String thumbnailPrompt,
    String previewVideoKey,
    String thumbnailKey,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    String previewVideoUrl,
    String thumbnailUrl,
    List<TemplateBlockResponse> blocks,
    List<TemplateAudioClipResponse> audioClips,
    Integer blockCount) {

  public static TemplateResponse of(Template t, StorageService storage) {
    return of(t, storage, null, null, null);
  }

  public static TemplateResponse of(
      Template t,
      StorageService storage,
      List<TemplateBlockResponse> blocks,
      List<TemplateAudioClipResponse> audioClips,
      Integer blockCount) {
    return new TemplateResponse(
        t.getId(),
        t.getCreator().getId(),
        t.getName(),
        t.getDescription(),
        t.getAvatarSlots(),
        t.getAvatarIds(),
        t.isPublished(),
        t.getThumbnailPrompt(),
        t.getPreviewVideoKey(),
        t.getThumbnailKey(),
        t.getCreatedAt(),
        t.getUpdatedAt(),
        t.getPreviewVideoKey() != null ? storage.getPublicUrl(t.getPreviewVideoKey()) : null,
        t.getThumbnailKey() != null ? storage.getPublicUrl(t.getThumbnailKey()) : null,
        blocks,
        audioClips,
        blockCount);
  }
}
