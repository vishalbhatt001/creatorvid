package com.pixovid.backend.openrouter;

import java.util.List;

public record VideoModel(
    String id,
    String name,
    String description,
    List<String> supportedResolutions,
    List<String> supportedAspectRatios,
    List<String> supportedSizes,
    /** Discrete clip lengths (seconds) the model accepts, e.g. veo-3.1 -> [4, 6, 8]. */
    List<Integer> supportedDurations,
    /** True if the model honors an audio input reference (for audio-driven lip-sync). */
    boolean supportsAudioInput,
    /** True if the model accepts reference images (usable as a face-swap edit model). */
    boolean supportsReferences) {}
