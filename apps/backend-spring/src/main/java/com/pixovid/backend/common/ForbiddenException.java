package com.pixovid.backend.common;

/** Thrown when an authenticated user lacks the required role. Mapped to 403 by {@link GlobalExceptionHandler}. */
public class ForbiddenException extends RuntimeException {

  public ForbiddenException(String message) {
    super(message);
  }
}
