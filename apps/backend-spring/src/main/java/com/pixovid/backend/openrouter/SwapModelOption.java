package com.pixovid.backend.openrouter;

/** A selectable face-swap model: the local FaceFusion service or an OpenRouter edit model. */
public record SwapModelOption(
    /** "facefusion" for the local service, otherwise an OpenRouter image model id. */
    String id,
    String name,
    /** True for the local (FaceFusion) option. */
    boolean local) {}
