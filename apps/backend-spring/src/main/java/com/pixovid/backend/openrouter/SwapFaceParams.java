package com.pixovid.backend.openrouter;

/**
 * Face swap via a diffusion image-edit model: {@code frame} is the base image and {@code face} is
 * a reference, so the model re-renders the frame with the person's face.
 */
public record SwapFaceParams(
    String model,
    /** The face to apply (avatar). */
    FaceImage face,
    /** The frame being edited (the face is swapped onto this). */
    FaceImage frame,
    /** Optional natural-language guidance, e.g. "keep the soft window lighting". */
    String context,
    String aspectRatio) {

  public record FaceImage(byte[] data, String mimeType) {}
}
