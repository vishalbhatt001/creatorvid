package com.pixovid.backend.template.render;

/** Persists per-block artifacts as they're produced (keyed by the block's id). */
@FunctionalInterface
public interface ArtifactsCallback {
  void onArtifacts(String blockId, BlockArtifacts artifacts);
}
