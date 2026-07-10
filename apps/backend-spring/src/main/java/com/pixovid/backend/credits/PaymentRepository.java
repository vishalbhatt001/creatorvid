package com.pixovid.backend.credits;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, String> {

  Optional<Payment> findByRazorpayOrderId(String razorpayOrderId);

  /** Atomically claims an order for fulfillment (only one caller wins); returns rows affected (0 or 1). */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "UPDATE Payment p SET p.status = :paid, p.razorpayPaymentId = :paymentId, p.razorpaySignature = :signature "
          + "WHERE p.razorpayOrderId = :orderId AND p.status <> :paid")
  int claimForFulfillment(
      @Param("orderId") String orderId,
      @Param("paymentId") String paymentId,
      @Param("signature") String signature,
      @Param("paid") PaymentStatus paid);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("UPDATE Payment p SET p.status = :newStatus WHERE p.razorpayOrderId = :orderId AND p.status = :currentStatus")
  int updateStatusIfCurrentlyStatus(
      @Param("orderId") String orderId,
      @Param("currentStatus") PaymentStatus currentStatus,
      @Param("newStatus") PaymentStatus newStatus);
}
