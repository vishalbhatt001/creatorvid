package com.pixovid.backend.template.render;

import com.pixovid.backend.template.TemplateBlock;
import lombok.Builder;

/** Immutable snapshot of a TemplateBlock's render-relevant fields (port of templateRender.ts's RenderBlock). */
@Builder
public record RenderBlockSpec(
    /** Source TemplateBlock id; used to key progress updates. */
    String id,
    String prompt,
    String model,
    String resolution,
    String aspectRatio,
    double startSec,
    double endSec,
    int track,
    Integer duration,
    Double cropStart,
    Double cropEnd,
    String startImageKey,
    String endImageKey,
    /** Cached "approved" face-swap previews of the start/end frames. */
    String swappedStartKey,
    String swappedEndKey,
    String videoKey,
    /** An admin-uploaded raw video used directly (no AI generation); always takes precedence. */
    String sourceVideoKey,
    String linkGroupId,
    boolean faceSwapStart,
    boolean faceSwapEnd,
    int avatarSlot,
    String swapContext,
    /** "facefusion" | an OpenRouter image model id | null = server default. */
    String swapModel,
    boolean lipsync) {

  public static RenderBlockSpec from(TemplateBlock b) {
    return RenderBlockSpec.builder()
        .id(b.getId())
        .prompt(b.getPrompt())
        .model(b.getModel())
        .resolution(b.getResolution())
        .aspectRatio(b.getAspectRatio())
        .startSec(b.getStartSec())
        .endSec(b.getEndSec())
        .track(b.getTrack())
        .duration(b.getDuration())
        .cropStart(b.getCropStart())
        .cropEnd(b.getCropEnd())
        .startImageKey(b.getStartImageKey())
        .endImageKey(b.getEndImageKey())
        .swappedStartKey(b.getSwappedStartKey())
        .swappedEndKey(b.getSwappedEndKey())
        .videoKey(b.getVideoKey())
        .sourceVideoKey(b.getSourceVideoKey())
        .linkGroupId(b.getLinkGroupId())
        .faceSwapStart(b.isFaceSwapStart())
        .faceSwapEnd(b.isFaceSwapEnd())
        .avatarSlot(b.getAvatarSlot())
        .swapContext(b.getSwapContext())
        .swapModel(b.getSwapModel())
        .lipsync(b.isLipsync())
        .build();
  }
}
