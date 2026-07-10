package com.pixovid.backend.credits;

import com.pixovid.backend.common.BaseEntity;
import com.pixovid.backend.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A Razorpay order to buy a credit pack. Credits are granted exactly once when the payment is
 * verified (the unique razorpayOrderId guards against double-grant).
 */
@Entity
@Table(name = "payment")
@Getter
@Setter
public class Payment extends BaseEntity {

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "userId", nullable = false)
  private User user;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false)
  private PaymentStatus status = PaymentStatus.CREATED;

  /** The pack the user is buying (see CREDIT_PACKS in CreditsService). */
  @Column(name = "packId", nullable = false)
  private String packId;

  /** Amount in the smallest currency unit (paise for INR). */
  @Column(name = "amount", nullable = false)
  private int amount;

  @Column(name = "currency", nullable = false)
  private String currency = "INR";

  /** Credits to grant on success (base pack credits + any bonus). */
  @Column(name = "credits", nullable = false)
  private int credits;

  @Column(name = "razorpayOrderId", nullable = false, unique = true)
  private String razorpayOrderId;

  @Column(name = "razorpayPaymentId")
  private String razorpayPaymentId;

  @Column(name = "razorpaySignature")
  private String razorpaySignature;
}
