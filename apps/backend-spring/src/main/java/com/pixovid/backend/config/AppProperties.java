package com.pixovid.backend.config;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds the {@code app.*} tree in application.yml — mirrors apps/backend/src/env.ts's variable names. */
@ConfigurationProperties(prefix = "app")
@Getter
@Setter
public class AppProperties {

  private String backendUrl;
  private List<String> frontendUrl;
  private List<String> adminEmails = List.of();
  private List<String> superadminEmails = List.of();

  private Auth auth = new Auth();
  private OpenRouter openrouter = new OpenRouter();
  private Swap swap = new Swap();
  private Render render = new Render();
  private Razorpay razorpay = new Razorpay();
  private Credits credits = new Credits();
  private Minio minio = new Minio();

  @Getter
  @Setter
  public static class Auth {
    private String sessionSecret;
    private String googleClientId;
    private String googleClientSecret;

    public boolean googleConfigured() {
      return googleClientId != null
          && !googleClientId.isBlank()
          && googleClientSecret != null
          && !googleClientSecret.isBlank();
    }
  }

  @Getter
  @Setter
  public static class OpenRouter {
    private String apiKey;
    private String baseUrl;
    private String thumbnailModel;
    private List<String> thumbnailFallbackModels = List.of();
    private String swapModel;

    public boolean configured() {
      return apiKey != null && !apiKey.isBlank();
    }
  }

  @Getter
  @Setter
  public static class Swap {
    /** "facefusion" or "flux". */
    private String provider = "facefusion";
    private String facefusionUrl;
  }

  @Getter
  @Setter
  public static class Render {
    private int blockConcurrency = 3;
    private int videoMaxAttempts = 3;
  }

  @Getter
  @Setter
  public static class Razorpay {
    private static final java.util.regex.Pattern KEY_ID_RE =
        java.util.regex.Pattern.compile("^rzp_(test|live)_");

    private String keyId;
    private String keySecret;
    private String webhookSecret;

    public String getKeyId() {
      return trimToNull(keyId);
    }

    public String getKeySecret() {
      return trimToNull(keySecret);
    }

    public String getWebhookSecret() {
      return trimToNull(webhookSecret);
    }

    public boolean configured() {
      String id = getKeyId();
      String secret = getKeySecret();
      return id != null && secret != null && KEY_ID_RE.matcher(id).lookingAt();
    }

    private static String trimToNull(String value) {
      if (value == null) return null;
      String trimmed = value.trim();
      return trimmed.isEmpty() ? null : trimmed;
    }
  }

  @Getter
  @Setter
  public static class Credits {
    private int perImage = 6;
    private int perVideo = 60;
    private int perTemplateRender = 1000;
    private double usdInrRate = 86;
  }

  @Getter
  @Setter
  public static class Minio {
    private String endpoint = "localhost";
    private String frontendEndpoint = "localhost";
    private int port = 9000;
    private boolean useSsl = false;
    private String accessKey = "minioadmin";
    private String secretKey = "minioadmin";
    private String bucket = "video-arena";
  }
}
