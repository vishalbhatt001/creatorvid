package com.pixovid.backend.template.dto;

import com.pixovid.backend.template.RenderBlockPhase;
import com.pixovid.backend.template.TemplateRenderBlock;
import java.time.LocalDateTime;

public record TemplateRenderBlockResponse(
    String id,
    String renderId,
    String blockId,
    int order,
    double startSec,
    double endSec,
    String label,
    RenderBlockPhase phase,
    int attempt,
    String error,
    String videoKey,
    String swappedStartKey,
    String swappedEndKey,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {

  public static TemplateRenderBlockResponse of(TemplateRenderBlock b) {
    return new TemplateRenderBlockResponse(
        b.getId(),
        b.getRender().getId(),
        b.getBlockId(),
        b.getOrder(),
        b.getStartSec(),
        b.getEndSec(),
        b.getLabel(),
        b.getPhase(),
        b.getAttempt(),
        b.getError(),
        b.getVideoKey(),
        b.getSwappedStartKey(),
        b.getSwappedEndKey(),
        b.getCreatedAt(),
        b.getUpdatedAt());
  }
}
