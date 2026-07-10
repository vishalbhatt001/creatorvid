package com.pixovid.backend.openrouter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pixovid.backend.config.AppProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.stream.StreamSupport;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Port of apps/backend/src/lib/openrouter.ts: OpenRouter video/image generation + model listing. */
@Component
public class OpenRouterClient {

  private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);
  private static final Duration MAX_POLL = Duration.ofMinutes(10);
  private static final Duration HTTP_TIMEOUT = Duration.ofMinutes(2);

  private final AppProperties.OpenRouter config;
  private final RestClient restClient;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public OpenRouterClient(AppProperties appProperties) {
    this.config = appProperties.getOpenrouter();
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout((int) HTTP_TIMEOUT.toMillis());
    factory.setReadTimeout((int) HTTP_TIMEOUT.toMillis());
    this.restClient =
        RestClient.builder().baseUrl(config.getBaseUrl()).requestFactory(factory).build();
  }

  public boolean isConfigured() {
    return config.configured();
  }

  /**
   * Whether a video model honors an audio {@code input_references} entry (audio-driven lip-sync).
   * Per OpenRouter, audio references are currently only honored by BytePlus/ByteDance Seedance 2.0.
   */
  public static boolean supportsAudioLipsync(String modelId) {
    return modelId != null && modelId.toLowerCase(Locale.ROOT).contains("seedance-2");
  }

  private HttpHeaders authHeaders() {
    HttpHeaders headers = new HttpHeaders();
    if (config.configured()) {
      headers.setBearerAuth(config.getApiKey());
    }
    headers.setContentType(MediaType.APPLICATION_JSON);
    return headers;
  }

  private void requireConfigured() {
    if (!isConfigured()) {
      throw new OpenRouterException(
          "OPENROUTER_API_KEY is not configured. Set it in the backend env to generate videos.", null);
    }
  }

  /** List the video-generation models available on OpenRouter. */
  public List<VideoModel> listVideoModels() {
    JsonNode json = getJson("/videos/models");
    List<VideoModel> models = new ArrayList<>();
    for (JsonNode m : arrayOf(json.get("data"))) {
      models.add(
          new VideoModel(
              text(m, "id"),
              text(m, "name"),
              text(m, "description"),
              stringList(m.get("supported_resolutions")),
              stringList(m.get("supported_aspect_ratios")),
              stringList(m.get("supported_sizes")),
              intList(m.get("supported_durations")),
              supportsAudioLipsync(text(m, "id")),
              false));
    }
    return models;
  }

  /** List image-generation models, normalised into the same shape video models use. */
  public List<VideoModel> listImageModels() {
    JsonNode json = getJson("/images/models");
    List<VideoModel> models = new ArrayList<>();
    for (JsonNode m : arrayOf(json.get("data"))) {
      JsonNode params = m.get("supported_parameters");
      boolean supportsReferences =
          params != null && params.has("input_references") && !params.get("input_references").isNull();
      String name = text(m, "name");
      models.add(
          new VideoModel(
              text(m, "id"),
              name != null ? name : text(m, "id"),
              text(m, "description"),
              params != null ? stringList(valuesOf(params.get("resolution"))) : List.of(),
              params != null ? stringList(valuesOf(params.get("aspect_ratio"))) : List.of(),
              List.of(),
              List.of(),
              false,
              supportsReferences));
    }
    return models;
  }

  private JsonNode valuesOf(JsonNode capability) {
    return capability == null ? null : capability.get("values");
  }

  /** The face-swap models the admin can choose per block: local FaceFusion + reference-capable OpenRouter models. */
  public List<SwapModelOption> listSwapModels() {
    List<SwapModelOption> options = new ArrayList<>();
    options.add(new SwapModelOption("facefusion", "FaceFusion (local)", true));
    listImageModels().stream()
        .filter(VideoModel::supportsReferences)
        .forEach(m -> options.add(new SwapModelOption(m.id(), m.name(), false)));
    return options;
  }

  /** Guess an image content type from the leading bytes of the buffer. */
  private static String detectImageContentType(byte[] data) {
    if (data.length >= 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
      return "image/jpeg";
    }
    if (data.length >= 12 && new String(data, 8, 4).equals("WEBP")) {
      return "image/webp";
    }
    return "image/png";
  }

  /** Generate an image synchronously and return the decoded bytes. */
  public GeneratedImage generateImage(GenerateImageParams params) {
    requireConfigured();
    ObjectNode body = objectMapper.createObjectNode();
    body.put("model", params.model());
    body.put("prompt", params.prompt());
    if (params.resolution() != null) {
      body.put("resolution", params.resolution());
    }
    if (params.aspectRatio() != null) {
      body.put("aspect_ratio", params.aspectRatio());
    }
    if (params.references() != null && !params.references().isEmpty()) {
      var refs = body.putArray("input_references");
      for (ImageRef ref : params.references()) {
        ObjectNode refNode = refs.addObject();
        refNode.put("type", "image_url");
        refNode.putObject("image_url").put("url", ref.url());
      }
    }

    JsonNode json = postJson("/images", body);
    JsonNode first = json.has("data") && json.get("data").size() > 0 ? json.get("data").get(0) : null;
    Double cost = json.has("usage") && json.get("usage").has("cost") ? json.get("usage").get("cost").asDouble() : null;

    if (first != null && first.has("b64_json") && !first.get("b64_json").isNull()) {
      byte[] data = Base64.getDecoder().decode(first.get("b64_json").asText());
      return new GeneratedImage(data, detectImageContentType(data), cost);
    }
    if (first != null && first.has("url") && !first.get("url").isNull()) {
      String url = first.get("url").asText();
      var response = restClient.get().uri(url).retrieve().toEntity(byte[].class);
      String contentType = response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
      byte[] data = response.getBody();
      return new GeneratedImage(data, contentType != null ? contentType : detectImageContentType(data), cost);
    }

    String message = "No image returned";
    if (json.has("error")) {
      JsonNode error = json.get("error");
      message = error.isTextual() ? error.asText() : text(error, "message");
    }
    throw new OpenRouterException(message, null);
  }

  private static String asDataUrl(byte[] data, String mime) {
    return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(data);
  }

  /** Face swap via a diffusion image-edit model (e.g. FLUX.2), unlike FaceFusion this accepts a context prompt. */
  public GeneratedImage swapFaceWithImageModel(SwapFaceParams params) {
    String base =
        "You are given two images. IMAGE 1 is the scene to edit. IMAGE 2 is a reference photo of a "
            + "different person. Task: change the identity of the main face in IMAGE 1 so it becomes the "
            + "person from IMAGE 2 — copy IMAGE 2's facial features, bone structure, eyes, nose, mouth and "
            + "overall likeness. "
            + "Keep EVERYTHING ELSE from IMAGE 1 unchanged: the body, pose, the existing hair and beard, "
            + "clothing, framing, camera angle, lighting and background. "
            + "Do NOT import the hair, beard, glasses/sunglasses or accessories from IMAGE 2, and do not add "
            + "any that aren't already in IMAGE 1. "
            + "Match the skin tone and color to IMAGE 1's lighting so the face blends seamlessly. "
            + "Output a photorealistic result with a natural, neutral expression and change nothing other "
            + "than the facial identity. Preserve IMAGE 1's exact framing and aspect ratio.";
    String context = params.context() != null ? params.context().trim() : "";
    String prompt = context.isEmpty() ? base : base + "\n\nAdditional guidance from the creator: " + context;
    return generateImage(
        new GenerateImageParams(
            params.model(),
            prompt,
            null,
            params.aspectRatio(),
            List.of(
                new ImageRef(asDataUrl(params.frame().data(), params.frame().mimeType())),
                new ImageRef(asDataUrl(params.face().data(), params.face().mimeType())))));
  }

  /** Generate a video synchronously: submit the job, poll until terminal, then download the result. */
  public GeneratedVideo generateVideo(GenerateVideoParams params) {
    requireConfigured();
    ObjectNode body = objectMapper.createObjectNode();
    body.put("model", params.model());
    body.put("prompt", params.prompt());
    if (params.duration() != null) {
      body.put("duration", params.duration());
    }
    if (params.resolution() != null) {
      body.put("resolution", params.resolution());
    }
    if (params.aspectRatio() != null) {
      body.put("aspect_ratio", params.aspectRatio());
    }
    if (params.generateAudio() != null) {
      body.put("generate_audio", params.generateAudio());
    }

    var frameImages = objectMapper.createArrayNode();
    if (params.firstFrame() != null) {
      ObjectNode f = frameImages.addObject();
      f.put("type", "image_url");
      f.putObject("image_url").put("url", params.firstFrame().url());
      f.put("frame_type", "first_frame");
    }
    if (params.lastFrame() != null) {
      ObjectNode f = frameImages.addObject();
      f.put("type", "image_url");
      f.putObject("image_url").put("url", params.lastFrame().url());
      f.put("frame_type", "last_frame");
    }
    if (frameImages.size() > 0) {
      body.set("frame_images", frameImages);
    }

    var inputReferences = objectMapper.createArrayNode();
    if (params.references() != null) {
      for (ImageRef ref : params.references()) {
        ObjectNode r = inputReferences.addObject();
        r.put("type", "image_url");
        r.putObject("image_url").put("url", ref.url());
      }
    }
    if (params.audioReference() != null) {
      ObjectNode r = inputReferences.addObject();
      r.put("type", "audio_url");
      r.putObject("audio_url").put("url", params.audioReference().url());
    }
    if (inputReferences.size() > 0) {
      body.set("input_references", inputReferences);
    }

    JsonNode submitted = postJson("/videos", body);
    String jobId = text(submitted, "id");
    String pollingUrl = submitted.has("polling_url") ? text(submitted, "polling_url") : "/videos/" + jobId;

    Instant deadline = Instant.now().plus(MAX_POLL);
    JsonNode status = submitted;
    String statusValue = text(status, "status");
    while ("pending".equals(statusValue) || "in_progress".equals(statusValue)) {
      if (Instant.now().isAfter(deadline)) {
        throw new OpenRouterException(
            "Video generation timed out after " + MAX_POLL.toSeconds() + "s (job " + jobId + ")", null);
      }
      sleep(POLL_INTERVAL);
      status = getJson(pollingUrl);
      statusValue = text(status, "status");
    }

    if (!"completed".equals(statusValue)) {
      String error = text(status, "error");
      throw new OpenRouterException(
          error != null ? error : "Video generation " + statusValue + " (job " + jobId + ")", null);
    }

    Double cost = status.has("usage") && status.get("usage").has("cost") ? status.get("usage").get("cost").asDouble() : null;
    JsonNode unsignedUrls = status.get("unsigned_urls");
    String contentUrl =
        unsignedUrls != null && unsignedUrls.size() > 0
            ? unsignedUrls.get(0).asText()
            : "/videos/" + jobId + "/content?index=0";

    var response =
        restClient.get().uri(contentUrl).headers(h -> h.addAll(authHeaders())).retrieve().toEntity(byte[].class);
    String contentType = response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
    return new GeneratedVideo(
        response.getBody(), contentType != null ? contentType : "video/mp4", jobId, cost);
  }

  // ---------------------------------------------------------------------------
  // HTTP + JSON helpers
  // ---------------------------------------------------------------------------

  private JsonNode getJson(String path) {
    try {
      String body = restClient.get().uri(path).headers(h -> h.addAll(authHeaders())).retrieve().body(String.class);
      return objectMapper.readTree(body);
    } catch (RestClientResponseException e) {
      throw new OpenRouterException(
          "OpenRouter request failed: " + e.getStatusCode().value() + " " + e.getResponseBodyAsString(), e);
    } catch (Exception e) {
      throw new OpenRouterException("OpenRouter request failed", e);
    }
  }

  private JsonNode postJson(String path, ObjectNode body) {
    try {
      String responseBody =
          restClient
              .post()
              .uri(path)
              .headers(h -> h.addAll(authHeaders()))
              .body(body.toString())
              .retrieve()
              .body(String.class);
      return objectMapper.readTree(responseBody);
    } catch (RestClientResponseException e) {
      throw new OpenRouterException(
          "OpenRouter request failed: " + e.getStatusCode().value() + " " + e.getResponseBodyAsString(), e);
    } catch (Exception e) {
      throw new OpenRouterException("OpenRouter request failed", e);
    }
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new OpenRouterException("Interrupted while polling OpenRouter", e);
    }
  }

  private static Iterable<JsonNode> arrayOf(JsonNode node) {
    return node == null || !node.isArray() ? List.of() : () -> node.iterator();
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  private static List<String> stringList(JsonNode arrayNode) {
    if (arrayNode == null || !arrayNode.isArray()) {
      return List.of();
    }
    return StreamSupport.stream(arrayNode.spliterator(), false).map(JsonNode::asText).toList();
  }

  private static List<Integer> intList(JsonNode arrayNode) {
    if (arrayNode == null || !arrayNode.isArray()) {
      return List.of();
    }
    return StreamSupport.stream(arrayNode.spliterator(), false).map(JsonNode::asInt).toList();
  }

  public static class OpenRouterException extends RuntimeException {
    public OpenRouterException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
