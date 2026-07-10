package com.pixovid.backend.credits;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixovid.backend.config.AppProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Minimal Razorpay client over the REST API — port of apps/backend/src/lib/razorpay.ts (no SDK
 * dependency there either).
 */
@Component
public class RazorpayClient {

  private static final String RAZORPAY_API = "https://api.razorpay.com/v1";
  private static final String HMAC_SHA256 = "HmacSHA256";

  private final AppProperties.Razorpay config;
  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final ObjectMapper objectMapper = new ObjectMapper();

  public RazorpayClient(AppProperties appProperties) {
    this.config = appProperties.getRazorpay();
  }

  public boolean isConfigured() {
    return config.configured();
  }

  public record RazorpayOrder(String id, long amount, String currency, String status) {}

  /** Create an order. {@code amount} is in the smallest unit (paise for INR). */
  public RazorpayOrder createOrder(
      long amount, String currency, String receipt, Map<String, String> notes) {
    requireConfigured();
    String auth =
        Base64.getEncoder()
            .encodeToString((config.getKeyId() + ":" + config.getKeySecret()).getBytes(StandardCharsets.UTF_8));
    try {
      Map<String, Object> payload = new java.util.LinkedHashMap<>();
      payload.put("amount", amount);
      payload.put("currency", currency);
      payload.put("receipt", receipt);
      if (notes != null && !notes.isEmpty()) {
        payload.put("notes", notes);
      }
      String body = objectMapper.writeValueAsString(payload);
      HttpRequest request =
          HttpRequest.newBuilder(URI.create(RAZORPAY_API + "/orders"))
              .header("Authorization", "Basic " + auth)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        String authError = authFailureMessage(response.statusCode(), response.body());
        throw new RazorpayException(
            authError != null
                ? authError
                : "Razorpay order creation failed: " + response.statusCode() + " " + response.body(),
            null);
      }
      Map<?, ?> json = objectMapper.readValue(response.body(), Map.class);
      return new RazorpayOrder(
          (String) json.get("id"),
          ((Number) json.get("amount")).longValue(),
          (String) json.get("currency"),
          (String) json.get("status"));
    } catch (RazorpayException e) {
      throw e;
    } catch (Exception e) {
      throw new RazorpayException("Razorpay order creation failed", e);
    }
  }

  /** True if the (orderId, paymentId, signature) triple is authentic. */
  public boolean verifyPaymentSignature(String orderId, String paymentId, String signature) {
    requireConfigured();
    String expected = hmacSha256Hex(config.getKeySecret(), orderId + "|" + paymentId);
    return timingSafeEquals(expected, signature);
  }

  /** Validate a Razorpay webhook payload against the configured webhook secret. */
  public boolean verifyWebhookSignature(String rawBody, String signature) {
    if (config.getWebhookSecret() == null || config.getWebhookSecret().isBlank()) {
      return false;
    }
    String expected = hmacSha256Hex(config.getWebhookSecret(), rawBody);
    return timingSafeEquals(expected, signature);
  }

  private void requireConfigured() {
    if (!isConfigured()) {
      if (config.getKeyId() != null && config.getKeySecret() != null) {
        throw new RazorpayException(
            "RAZORPAY_KEY_ID looks invalid (expected rzp_test_… or rzp_live_…). Copy both keys from the Razorpay dashboard → Settings → API Keys.",
            null);
      }
      throw new RazorpayException(
          "Razorpay is not configured. Set RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET.", null);
    }
  }

  private static String authFailureMessage(int status, String body) {
    if (status == 401 || (body != null && body.contains("Authentication failed"))) {
      return "Razorpay authentication failed — verify RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET are a matching pair from the same mode (test or live) in the Razorpay dashboard → Settings → API Keys.";
    }
    return null;
  }

  private static String hmacSha256Hex(String secret, String message) {
    try {
      Mac mac = Mac.getInstance(HMAC_SHA256);
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
      byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (Exception e) {
      throw new RazorpayException("Failed to compute HMAC signature", e);
    }
  }

  private static boolean timingSafeEquals(String a, String b) {
    return MessageDigest.isEqual(
        a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }

  public static class RazorpayException extends RuntimeException {
    public RazorpayException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
