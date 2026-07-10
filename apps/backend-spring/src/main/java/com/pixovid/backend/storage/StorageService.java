package com.pixovid.backend.storage;

import com.pixovid.backend.config.AppProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.SetBucketPolicyArgs;
import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Port of apps/backend/src/lib/storage.ts: MinIO/S3-compatible object storage for uploads + generated media. */
@Service
public class StorageService {

  private final MinioClient client;
  private final AppProperties.Minio config;
  private final String publicBase;

  public StorageService(AppProperties appProperties) {
    this.config = appProperties.getMinio();
    String scheme = config.isUseSsl() ? "https" : "http";
    this.client =
        MinioClient.builder()
            .endpoint(String.format("%s://%s:%d", scheme, config.getEndpoint(), config.getPort()))
            .credentials(config.getAccessKey(), config.getSecretKey())
            .build();

    boolean isDefaultPort =
        (config.isUseSsl() && config.getPort() == 443) || (!config.isUseSsl() && config.getPort() == 80);
    String host =
        isDefaultPort ? config.getFrontendEndpoint() : config.getFrontendEndpoint() + ":" + config.getPort();
    this.publicBase = scheme + "://" + host + "/" + config.getBucket();
  }

  /** Ensure the bucket exists and allows anonymous reads. Safe to call repeatedly. */
  @PostConstruct
  public void ensureBucket() {
    try {
      boolean exists =
          client.bucketExists(BucketExistsArgs.builder().bucket(config.getBucket()).build());
      if (!exists) {
        client.makeBucket(MakeBucketArgs.builder().bucket(config.getBucket()).build());
      }
      client.setBucketPolicy(
          SetBucketPolicyArgs.builder().bucket(config.getBucket()).config(publicReadPolicy()).build());
    } catch (Exception e) {
      throw new StorageException("Failed to ensure MinIO bucket \"" + config.getBucket() + "\" exists", e);
    }
  }

  private String publicReadPolicy() {
    return """
        {
          "Version": "2012-10-17",
          "Statement": [
            {
              "Effect": "Allow",
              "Principal": {"AWS": ["*"]},
              "Action": ["s3:GetObject"],
              "Resource": ["arn:aws:s3:::%s/*"]
            }
          ]
        }
        """
        .formatted(config.getBucket());
  }

  /** Upload bytes under {@code <prefix>/<uuid>[.ext]} and return the object key. */
  public String uploadBuffer(byte[] data, String contentType, String prefix) {
    return uploadBuffer(data, contentType, prefix, null);
  }

  public String uploadBuffer(byte[] data, String contentType, String prefix, String extension) {
    String ext = (extension == null || extension.isBlank()) ? "" : "." + extension.replaceFirst("^\\.", "");
    String key = prefix + "/" + UUID.randomUUID() + ext;
    try (InputStream stream = new ByteArrayInputStream(data)) {
      client.putObject(
          PutObjectArgs.builder()
              .bucket(config.getBucket())
              .object(key)
              .stream(stream, (long) data.length, -1L)
              .contentType(contentType)
              // Per-object public-read ACL: on hosted stores (e.g. DigitalOcean Spaces) a
              // bucket-wide policy often can't be set with a scoped key. Harmless on local MinIO.
              .headers(Map.of("x-amz-acl", "public-read"))
              .build());
    } catch (Exception e) {
      throw new StorageException("Failed to upload object to MinIO", e);
    }
    return key;
  }

  /** Build a public, permanent URL for a stored object (bucket is anonymous-read). */
  public String getPublicUrl(String key) {
    String encoded =
        java.util.Arrays.stream(key.split("/"))
            .map(segment -> URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"))
            .reduce((a, b) -> a + "/" + b)
            .orElse(key);
    return publicBase + "/" + encoded;
  }

  /** Download an object's bytes. */
  public byte[] downloadObject(String key) {
    try (InputStream stream =
        client.getObject(GetObjectArgs.builder().bucket(config.getBucket()).object(key).build())) {
      return stream.readAllBytes();
    } catch (Exception e) {
      throw new StorageException("Failed to download object \"" + key + "\" from MinIO", e);
    }
  }

  public static class StorageException extends RuntimeException {
    public StorageException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
