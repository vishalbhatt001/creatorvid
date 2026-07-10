package com.pixovid.backend.credits;

import com.pixovid.backend.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * A single append-only change to a user's credit balance. Sum of {@code amount} across a user's
 * rows equals {@code User.credits}. Unlike most models this has no {@code updatedAt}: entries are
 * never modified after creation.
 */
@Entity
@Table(name = "credit_transaction")
@Getter
@Setter
public class CreditTransaction {

  @Id
  @GeneratedValue
  @UuidGenerator
  @Column(name = "id", updatable = false, nullable = false)
  private String id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "userId", nullable = false)
  private User user;

  @Enumerated(EnumType.STRING)
  @Column(name = "type", nullable = false)
  private CreditTxnType type;

  /** Signed delta applied to the balance (positive = added, negative = spent). */
  @Column(name = "amount", nullable = false)
  private int amount;

  /** The user's balance immediately after this transaction (audit convenience). */
  @Column(name = "balanceAfter", nullable = false)
  private int balanceAfter;

  @Column(name = "description")
  private String description;

  /** What this transaction relates to: {@code referenceType} in video/image/template_render/payment. */
  @Column(name = "referenceType")
  private String referenceType;

  @Column(name = "referenceId")
  private String referenceId;

  @CreationTimestamp
  @Column(name = "createdAt", nullable = false, updatable = false)
  private LocalDateTime createdAt;
}
