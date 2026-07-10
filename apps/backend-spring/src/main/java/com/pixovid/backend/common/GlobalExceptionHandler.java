package com.pixovid.backend.common;

import com.pixovid.backend.credits.InsufficientCreditsException;
import com.pixovid.backend.credits.RazorpayClient;
import com.pixovid.backend.openrouter.OpenRouterClient;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

  /** Fallback for call sites that don't build a custom "this action costs N credits" message. */
  @ExceptionHandler(InsufficientCreditsException.class)
  public ResponseEntity<Map<String, String>> handleInsufficientCredits(InsufficientCreditsException ex) {
    return ResponseEntity.status(402).body(Map.of("error", ex.getMessage()));
  }

  /** Matches models.ts's modelsHandler: upstream OpenRouter failures surface as 502. */
  @ExceptionHandler(OpenRouterClient.OpenRouterException.class)
  public ResponseEntity<Map<String, String>> handleOpenRouter(OpenRouterClient.OpenRouterException ex) {
    return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
        .body(Map.of("error", ex.getMessage() != null ? ex.getMessage() : "Failed to load models"));
  }

  @ExceptionHandler(UnauthorizedException.class)
  public ResponseEntity<Map<String, String>> handleUnauthorized(UnauthorizedException ex) {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized"));
  }

  @ExceptionHandler(ForbiddenException.class)
  public ResponseEntity<Map<String, String>> handleForbidden(ForbiddenException ex) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", ex.getMessage()));
  }

  @ExceptionHandler(BadRequestException.class)
  public ResponseEntity<Map<String, String>> handleBadRequest(BadRequestException ex) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
  }

  @ExceptionHandler(NotFoundException.class)
  public ResponseEntity<Map<String, String>> handleNotFound(NotFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
  }

  /** Defense in depth for routes that don't pre-check RazorpayClient#isConfigured(). */
  @ExceptionHandler(RazorpayClient.RazorpayException.class)
  public ResponseEntity<Map<String, String>> handleRazorpay(RazorpayClient.RazorpayException ex) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", ex.getMessage()));
  }
}
