package com.pixovid.backend.ffmpeg;

/**
 * One slice of the output timeline: either a trimmed portion of a clip ({@code clip} = index into
 * the clips list, taken from {@code inPoint} for {@code length} seconds) or a black gap
 * ({@code clip} = null).
 */
public record TimelineSegment(Integer clip, double inPoint, double length) {}
