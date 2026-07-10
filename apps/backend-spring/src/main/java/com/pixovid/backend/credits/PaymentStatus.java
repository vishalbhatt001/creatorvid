package com.pixovid.backend.credits;

/** Lifecycle of a Razorpay credit purchase. */
public enum PaymentStatus {
  /** Order created, awaiting payment. */
  CREATED,
  /** Payment verified, credits granted. */
  PAID,
  /** Payment failed / abandoned. */
  FAILED
}
