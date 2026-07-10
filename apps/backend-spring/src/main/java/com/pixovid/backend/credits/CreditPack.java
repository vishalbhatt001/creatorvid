package com.pixovid.backend.credits;

/** A fixed top-up tier a user can buy. Price is in rupees (display) / paise (charged via Razorpay). */
public record CreditPack(
    String id,
    String name,
    String description,
    int priceInr,
    long amountPaise,
    int baseCredits,
    int bonusCredits) {

  public int totalCredits() {
    return baseCredits + bonusCredits;
  }
}
