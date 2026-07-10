package com.pixovid.backend.template;

/** Per-block phase within a template render, surfaced on the live progress page. */
public enum RenderBlockPhase {
  /** Waiting for a worker slot. */
  QUEUED,
  /** Swapping the user's face onto the block's frame(s). */
  FACE_SWAP,
  /** Generating the clip via the provider. */
  VIDEO_GENERATION,
  /** A generation attempt failed; trying again. */
  RETRYING,
  /** Generation done; clip is being composited into the timeline. */
  STITCHING,
  /** Clip generated successfully. */
  COMPLETED,
  /** No work needed (uploaded source clip or reused baked clip). */
  REUSED,
  /** Generation failed; reused the template's baked clip instead. */
  FELL_BACK,
  /** Generation failed and no fallback was available. */
  FAILED
}
