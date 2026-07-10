package com.pixovid.backend.ffmpeg;

/** One positioned slice of audio for a block window (relative to the window start). */
public record AudioWindowPart(
    byte[] data,
    /** In-point into the source audio (seconds). */
    double inPoint,
    /** Length of the slice (seconds). */
    double length,
    /** Offset from the window start where this slice begins (seconds). */
    double delaySec) {}
