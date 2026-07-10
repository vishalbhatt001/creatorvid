package com.pixovid.backend.common;

/** Thrown when a request has no valid session. Mapped to 401 by {@link GlobalExceptionHandler}. */
public class UnauthorizedException extends RuntimeException {

  public UnauthorizedException(String message) {
    super(message);
  }
}
