package com.pixovid.backend.common;

/** Thrown when a requested resource doesn't exist (or isn't owned by the caller). Mapped to 404. */
public class NotFoundException extends RuntimeException {

  public NotFoundException(String message) {
    super(message);
  }
}
