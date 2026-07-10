package com.pixovid.backend.credits;

/** Why a credit balance changed. */
public enum CreditTxnType {
  /** Bought via a successful Razorpay payment. */
  PURCHASE,
  /** Consumed by a generation. */
  SPEND,
  /** Returned to the user after a failed generation. */
  REFUND,
  /** Promotional / pack bonus credits. */
  BONUS,
  /** Manual admin correction. */
  ADJUSTMENT
}
