package com.pixovid.backend.generation;

/** Shared lifecycle status for every kind of generation (video, image, face swap, avatar). */
public enum GenerationStatus {
  PENDING,
  IN_PROGRESS,
  COMPLETED,
  FAILED
}
