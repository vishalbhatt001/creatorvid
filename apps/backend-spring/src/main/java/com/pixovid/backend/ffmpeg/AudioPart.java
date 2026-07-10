package com.pixovid.backend.ffmpeg;

/**
 * One positioned audio clip to mix into the output: the slice {@code [inPoint, inPoint + length)}
 * of {@code data}, delayed to begin at {@code startSec}. Overlapping parts are summed together.
 */
public record AudioPart(byte[] data, double startSec, double inPoint, double length) {}
