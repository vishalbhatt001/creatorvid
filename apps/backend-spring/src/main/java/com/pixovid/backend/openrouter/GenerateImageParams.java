package com.pixovid.backend.openrouter;

import java.util.List;

public record GenerateImageParams(
    String model, String prompt, String resolution, String aspectRatio, List<ImageRef> references) {

  public GenerateImageParams(String model, String prompt) {
    this(model, prompt, null, null, null);
  }
}
