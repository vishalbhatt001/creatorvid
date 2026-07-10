package com.pixovid.backend.template.render;

import com.pixovid.backend.template.TemplateAudioClip;

public record RenderAudioClipSpec(String audioKey, double startSec, Double duration, Double cropStart, Double cropEnd) {

  public static RenderAudioClipSpec from(TemplateAudioClip c) {
    return new RenderAudioClipSpec(c.getAudioKey(), c.getStartSec(), c.getDuration(), c.getCropStart(), c.getCropEnd());
  }
}
