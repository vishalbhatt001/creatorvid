package com.pixovid.backend.template.render;

/** Newly-produced artifacts for a block, reported so the caller can persist them. */
public record BlockArtifacts(String videoKey, String swappedStartKey, String swappedEndKey) {}
