package com.pixovid.backend.openrouter;

import java.util.List;

public record GenerateVideoParams(
    String model,
    String prompt,
    Integer duration,
    String resolution,
    String aspectRatio,
    Boolean generateAudio,
    ImageRef firstFrame,
    ImageRef lastFrame,
    List<ImageRef> references,
    /** Audio track the video should lip-sync to (only honored by capable models). */
    AudioRef audioReference) {

  public record AudioRef(String url) {}
}
