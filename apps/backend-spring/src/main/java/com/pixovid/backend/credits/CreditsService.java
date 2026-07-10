package com.pixovid.backend.credits;

import com.pixovid.backend.config.AppProperties;
import com.pixovid.backend.user.User;
import com.pixovid.backend.user.UserRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Credits & billing helpers — port of apps/backend/src/lib/credits.ts. Users buy credits
 * (Razorpay) and spend a fixed number of credits per action, independent of which model they
 * pick. Credit mutations always go through this service so User.credits and the CreditTransaction
 * ledger stay consistent.
 */
@Service
public class CreditsService {

  /**
   * Three fixed top-up tiers. Base value is ~₹1/credit at the Starter tier; larger packs add a
   * bonus but stay priced so the per-action credit costs keep >= ~30% margin even at the most
   * generous (Studio) tier.
   */
  public static final List<CreditPack> CREDIT_PACKS =
      List.of(
          new CreditPack("starter", "Starter", "Enough credits to try things out.", 499, 49900, 500, 0),
          new CreditPack(
              "pro", "Pro", "Best for regular creators — 10% bonus credits.", 1999, 199900, 2000, 200),
          new CreditPack(
              "studio", "Studio", "For heavy use — 20% bonus credits.", 4999, 499900, 5000, 1000));

  private final UserRepository users;
  private final CreditTransactionRepository creditTransactions;
  private final PaymentRepository payments;
  private final AppProperties.Credits config;

  public CreditsService(
      UserRepository users,
      CreditTransactionRepository creditTransactions,
      PaymentRepository payments,
      AppProperties appProperties) {
    this.users = users;
    this.creditTransactions = creditTransactions;
    this.payments = payments;
    this.config = appProperties.getCredits();
  }

  public int actionCost(GenerationAction action) {
    return switch (action) {
      case VIDEO -> config.getPerVideo();
      case IMAGE -> config.getPerImage();
      case TEMPLATE_RENDER -> config.getPerTemplateRender();
    };
  }

  public static Optional<CreditPack> findPack(String packId) {
    return CREDIT_PACKS.stream().filter(p -> p.id().equals(packId)).findFirst();
  }

  public int getBalance(String userId) {
    return users.findById(userId).map(User::getCredits).orElse(0);
  }

  public record LedgerRef(String referenceType, String referenceId, String description) {
    public static LedgerRef of(String referenceType, String referenceId, String description) {
      return new LedgerRef(referenceType, referenceId, description);
    }
  }

  /**
   * Atomically spend {@code amount} credits. Uses a conditional update so concurrent requests
   * can't drive the balance negative. Throws {@link InsufficientCreditsException} if the user
   * doesn't have enough. Returns the new balance.
   */
  @Transactional
  public int spendCredits(String userId, int amount, LedgerRef ref) {
    if (amount <= 0) {
      return getBalance(userId);
    }
    int updated = users.decrementCreditsIfEnough(userId, amount);
    if (updated == 0) {
      throw new InsufficientCreditsException(amount, getBalance(userId));
    }
    int after = getBalance(userId);
    recordLedgerRow(userId, CreditTxnType.SPEND, -amount, after, ref);
    return after;
  }

  /**
   * Add credits to a user and record a ledger row. Used for purchases, refunds, bonuses and
   * admin adjustments. Returns the new balance.
   */
  @Transactional
  public int addCredits(String userId, int amount, CreditTxnType type, LedgerRef ref) {
    if (amount <= 0) {
      return getBalance(userId);
    }
    users.incrementCredits(userId, amount);
    int after = getBalance(userId);
    recordLedgerRow(userId, type, amount, after, ref);
    return after;
  }

  /**
   * Refund credits previously spent on a generation. Idempotent and retry-safe: never refunds
   * more than the net amount still outstanding for this reference (sum of SPEND minus prior
   * REFUNDs), so a double failure-callback won't double-refund, yet a
   * charge→refund→re-charge (retry) cycle refunds correctly. Safe to call even if nothing was charged.
   */
  @Transactional
  public void refundCredits(String userId, int amount, String referenceType, String referenceId, String description) {
    if (amount <= 0) {
      return;
    }
    List<CreditTransaction> rows = creditTransactions.findByReferenceTypeAndReferenceId(referenceType, referenceId);
    int net =
        rows.stream()
            .filter(r -> r.getType() == CreditTxnType.SPEND || r.getType() == CreditTxnType.REFUND)
            .mapToInt(CreditTransaction::getAmount)
            .sum(); // <= 0 if still owed (SPEND amounts negative, REFUND amounts positive)
    int outstanding = -net;
    int toRefund = Math.min(amount, outstanding);
    if (toRefund <= 0) {
      return;
    }
    addCredits(
        userId,
        toRefund,
        CreditTxnType.REFUND,
        LedgerRef.of(referenceType, referenceId, description != null ? description : "Refund for failed generation"));
  }

  public List<CreditTransaction> recentTransactions(String userId, int limit) {
    return creditTransactions.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, limit));
  }

  public record FulfillResult(boolean granted, String userId) {}

  /**
   * Grant a paid order's credits exactly once. Flips CREATED/FAILED -> PAID atomically; if it
   * was already PAID (e.g. the webhook beat the client's /verify call) this is a no-op idempotent
   * success. Returns null if no payment exists for the order.
   */
  @Transactional
  public FulfillResult fulfillPayment(String orderId, String paymentId, String signature) {
    Payment payment = payments.findByRazorpayOrderId(orderId).orElse(null);
    if (payment == null) {
      return null;
    }

    int claimed = payments.claimForFulfillment(orderId, paymentId, signature, PaymentStatus.PAID);
    if (claimed == 0) {
      // Already fulfilled by another path (webhook vs. client /verify race) — idempotent success.
      return new FulfillResult(false, payment.getUser().getId());
    }

    Optional<CreditPack> pack = findPack(payment.getPackId());
    int baseCredits = pack.map(CreditPack::baseCredits).orElse(payment.getCredits());
    int bonusCredits = pack.map(CreditPack::bonusCredits).orElse(0);
    String packName = pack.map(CreditPack::name).orElse("Credit");

    addCredits(
        payment.getUser().getId(),
        baseCredits,
        CreditTxnType.PURCHASE,
        LedgerRef.of("payment", payment.getId(), packName + " pack"));
    if (bonusCredits > 0) {
      addCredits(
          payment.getUser().getId(),
          bonusCredits,
          CreditTxnType.BONUS,
          LedgerRef.of("payment", payment.getId(), packName + " pack bonus"));
    }
    return new FulfillResult(true, payment.getUser().getId());
  }

  private void recordLedgerRow(String userId, CreditTxnType type, int signedAmount, int balanceAfter, LedgerRef ref) {
    CreditTransaction txn = new CreditTransaction();
    txn.setUser(users.getReferenceById(userId));
    txn.setType(type);
    txn.setAmount(signedAmount);
    txn.setBalanceAfter(balanceAfter);
    if (ref != null) {
      txn.setDescription(ref.description());
      txn.setReferenceType(ref.referenceType());
      txn.setReferenceId(ref.referenceId());
    }
    creditTransactions.save(txn);
  }
}
