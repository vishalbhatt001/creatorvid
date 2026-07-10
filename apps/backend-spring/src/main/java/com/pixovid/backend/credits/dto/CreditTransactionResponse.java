package com.pixovid.backend.credits.dto;

import com.pixovid.backend.credits.CreditTransaction;
import com.pixovid.backend.credits.CreditTxnType;
import java.time.LocalDateTime;

/** Serializes a CreditTransaction row without touching its lazy `user` association. */
public record CreditTransactionResponse(
    String id,
    CreditTxnType type,
    int amount,
    int balanceAfter,
    String description,
    String referenceType,
    String referenceId,
    LocalDateTime createdAt) {

  public static CreditTransactionResponse of(CreditTransaction t) {
    return new CreditTransactionResponse(
        t.getId(),
        t.getType(),
        t.getAmount(),
        t.getBalanceAfter(),
        t.getDescription(),
        t.getReferenceType(),
        t.getReferenceId(),
        t.getCreatedAt());
  }
}
