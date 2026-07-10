package com.pixovid.backend.template.dto;

import com.pixovid.backend.storage.StorageService;
import com.pixovid.backend.template.TemplateAudioClip;
import java.time.LocalDateTime;

public record TemplateAudioClipResponse(
    String id,
    String templateId,
    int order,
    double startSec,
    double endSec,
    int track,
    String audioKey,
    String name,
    double duration,
    double cropStart,
    Double cropEnd,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    String audioUrl) {

  public static TemplateAudioClipResponse of(TemplateAudioClip c, StorageService storage) {
    return new TemplateAudioClipResponse(
        c.getId(),
        c.getTemplate().getId(),
        c.getOrder(),
        c.getStartSec(),
        c.getEndSec(),
        c.getTrack(),
        c.getAudioKey(),
        c.getName(),
        c.getDuration(),
        c.getCropStart(),
        c.getCropEnd(),
        c.getCreatedAt(),
        c.getUpdatedAt(),
        storage.getPublicUrl(c.getAudioKey()));
  }
}
