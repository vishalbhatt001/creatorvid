package com.pixovid.backend.facefusion;

import com.pixovid.backend.config.AppProperties;
import java.time.Duration;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Port of apps/backend/src/lib/facefusion.ts: HTTP client for the self-hosted FaceFusion swap service. */
@org.springframework.stereotype.Component
public class FaceFusionClient {

  // Face swapping (downloading models + processing) can take a while on CPU.
  private static final Duration FACE_SWAP_TIMEOUT = Duration.ofMinutes(15);

  private final AppProperties.Swap config;
  private final RestClient restClient;

  public FaceFusionClient(AppProperties appProperties) {
    this.config = appProperties.getSwap();
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout((int) FACE_SWAP_TIMEOUT.toMillis());
    factory.setReadTimeout((int) FACE_SWAP_TIMEOUT.toMillis());
    this.restClient = RestClient.builder().requestFactory(factory).build();
  }

  public record FaceSwapInput(byte[] data, String mimeType, String filename) {}

  public record FaceSwapResult(byte[] data, String contentType) {}

  /** {@code source} is the face to apply; {@code target} is the base image being modified. */
  public FaceSwapResult faceSwap(FaceSwapInput source, FaceSwapInput target) {
    MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
    form.add("source", asResource(source));
    form.add("target", asResource(target));

    try {
      var response =
          restClient
              .post()
              .uri(config.getFacefusionUrl() + "/swap")
              .contentType(MediaType.MULTIPART_FORM_DATA)
              .body(form)
              .retrieve()
              .toEntity(byte[].class);
      String contentType =
          response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE) != null
              ? response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)
              : "image/jpeg";
      return new FaceSwapResult(response.getBody(), contentType);
    } catch (RestClientException e) {
      throw new FaceFusionException(
          "Could not reach the FaceFusion service at "
              + config.getFacefusionUrl()
              + " ("
              + e.getMessage()
              + "). Start it with `docker compose --profile facefusion up -d facefusion`.",
          e);
    }
  }

  private ByteArrayResource asResource(FaceSwapInput input) {
    return new ByteArrayResource(input.data()) {
      @Override
      public String getFilename() {
        return input.filename();
      }
    };
  }

  public static class FaceFusionException extends RuntimeException {
    public FaceFusionException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
