package com.pixovid.backend.template.render;

import com.pixovid.backend.template.RenderBlockPhase;

/** Reports a block's progress during a render (keyed by the block's id). Null args mean "leave unchanged". */
@FunctionalInterface
public interface ProgressCallback {
  void onProgress(String blockId, RenderBlockPhase phase, Integer attempt, String error);
}
