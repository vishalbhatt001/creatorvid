package com.pixovid.backend.common;

import java.util.Base64;
import java.util.Map;
import org.springframework.web.multipart.MultipartFile;

/** Port of apps/backend/src/lib/uploads.ts's small helpers. */
public final class MediaUtils {

  private static final Map<String, String> IMAGE_EXTENSIONS =
      Map.of("image/png", "png", "image/jpeg", "jpg", "image/webp", "webp", "image/gif", "gif");

  private MediaUtils() {}

  /** Map an image mime type to a file extension (defaults to png). */
  public static String extFromMime(String mime) {
    return IMAGE_EXTENSIONS.getOrDefault(mime, "png");
  }

  /** Encode an uploaded file as a base64 data URL (sent to providers that can't reach MinIO). */
  public static String toDataUrl(MultipartFile file) {
    try {
      return "data:" + file.getContentType() + ";base64," + Base64.getEncoder().encodeToString(file.getBytes());
    } catch (java.io.IOException e) {
      throw new BadRequestException("Failed to read uploaded file \"" + file.getOriginalFilename() + "\"");
    }
  }
}
