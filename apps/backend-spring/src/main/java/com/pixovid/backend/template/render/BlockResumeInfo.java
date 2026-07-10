package com.pixovid.backend.template.render;

/** Artifacts a prior attempt persisted for a block, used to resume instead of redo. */
public record BlockResumeInfo(String videoKey, String swappedStartKey, String swappedEndKey) {}
