package com.pixovid.backend.common;

/** Thrown for client input errors (validation, duplicate resource, etc). Mapped to 400 by {@link GlobalExceptionHandler}. */
public class BadRequestException extends RuntimeException {

  public BadRequestException(String message) {
    super(message);
  }
}
