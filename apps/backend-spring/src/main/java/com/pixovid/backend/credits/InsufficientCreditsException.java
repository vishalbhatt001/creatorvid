package com.pixovid.backend.credits;

public class InsufficientCreditsException extends RuntimeException {

  public final int required;
  public final int available;

  public InsufficientCreditsException(int required, int available) {
    super("Insufficient credits: need " + required + ", have " + available + ".");
    this.required = required;
    this.available = available;
  }
}
