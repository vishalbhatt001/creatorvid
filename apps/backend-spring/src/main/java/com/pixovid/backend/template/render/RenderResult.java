package com.pixovid.backend.template.render;

public record RenderResult(
    byte[] videoBuffer, String contentType, byte[] thumbnailBuffer, String thumbnailContentType, double cost) {}
