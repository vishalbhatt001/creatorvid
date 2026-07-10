package com.pixovid.backend.credits;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pixovid.backend.common.BadRequestException;
import com.pixovid.backend.common.NotFoundException;
import com.pixovid.backend.config.AppProperties;
import com.pixovid.backend.credits.dto.CreditTransactionResponse;
import com.pixovid.backend.user.User;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Port of apps/backend/src/routes/credits.ts: balance/ledger, packs, checkout, verify, and the Razorpay webhook backstop. */
@RestController
@RequestMapping("/api/credits")
public class CreditsController {

  private static final Logger log = LoggerFactory.getLogger(CreditsController.class);

  private final CreditsService creditsService;
  private final RazorpayClient razorpay;
  private final PaymentRepository paymentRepository;
  private final AppProperties.Razorpay config;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public CreditsController(
      CreditsService creditsService, RazorpayClient razorpay, PaymentRepository paymentRepository, AppProperties appProperties) {
    this.creditsService = creditsService;
    this.razorpay = razorpay;
    this.paymentRepository = paymentRepository;
    this.config = appProperties.getRazorpay();
  }

  public record BalanceResponse(int balance, List<CreditTransactionResponse> transactions) {}

  @GetMapping
  public BalanceResponse balance(@AuthenticationPrincipal User user) {
    return new BalanceResponse(
        creditsService.getBalance(user.getId()),
        creditsService.recentTransactions(user.getId(), 50).stream().map(CreditTransactionResponse::of).toList());
  }

  public record PackResponse(
      String id, String name, String description, int priceInr, int credits, int baseCredits, int bonusCredits) {}

  public record ActionCosts(int video, int image, int template_render) {}

  public record PacksResponse(
      String currency, boolean razorpayConfigured, String razorpayKeyId, List<PackResponse> packs, ActionCosts actionCosts) {}

  /** Available packs + per-action prices + checkout config for the frontend. */
  @GetMapping("/packs")
  public PacksResponse packs() {
    List<PackResponse> packResponses =
        CreditsService.CREDIT_PACKS.stream()
            .map(
                p ->
                    new PackResponse(
                        p.id(), p.name(), p.description(), p.priceInr(), p.totalCredits(), p.baseCredits(), p.bonusCredits()))
            .toList();
    return new PacksResponse(
        "INR",
        razorpay.isConfigured(),
        nullIfBlank(config.getKeyId()),
        packResponses,
        new ActionCosts(
            creditsService.actionCost(GenerationAction.VIDEO),
            creditsService.actionCost(GenerationAction.IMAGE),
            creditsService.actionCost(GenerationAction.TEMPLATE_RENDER)));
  }

  public record CheckoutRequest(String packId) {}

  public record CheckoutResponse(
      String orderId, long amount, String currency, String razorpayKeyId, String packId, String packName, int credits) {}

  /** Create a Razorpay order for a pack and persist a pending Payment row. */
  @PostMapping("/checkout")
  public ResponseEntity<?> checkout(@AuthenticationPrincipal User user, @RequestBody CheckoutRequest request) {
    if (!razorpay.isConfigured()) {
      return ResponseEntity.status(503).body(Map.of("error", "Payments are not configured on this server."));
    }
    if (request.packId() == null || request.packId().isBlank()) {
      throw new BadRequestException("packId is required");
    }
    CreditPack pack = CreditsService.findPack(request.packId()).orElseThrow(() -> new NotFoundException("Unknown pack."));

    try {
      int credits = pack.totalCredits();
      // Razorpay caps receipt at 40 chars; use a short unique token, keep userId/pack in our own DB row.
      String receipt = "rcpt_" + UUID.randomUUID().toString().replace("-", "");
      RazorpayClient.RazorpayOrder order =
          razorpay.createOrder(
              pack.amountPaise(),
              "INR",
              receipt,
              Map.of("userId", user.getId(), "packId", pack.id(), "credits", String.valueOf(credits)));

      Payment payment = new Payment();
      payment.setUser(user);
      payment.setPackId(pack.id());
      payment.setAmount((int) pack.amountPaise());
      payment.setCurrency("INR");
      payment.setCredits(credits);
      payment.setRazorpayOrderId(order.id());
      payment.setStatus(PaymentStatus.CREATED);
      paymentRepository.save(payment);

      return ResponseEntity.status(201)
          .body(
              new CheckoutResponse(
                  order.id(), order.amount(), order.currency(), nullIfBlank(config.getKeyId()), pack.id(), pack.name(), credits));
    } catch (Exception e) {
      String message = e.getMessage() != null ? e.getMessage() : "Failed to start checkout";
      log.error("Checkout failed: {}", message);
      return ResponseEntity.status(502).body(Map.of("error", message));
    }
  }

  public record VerifyRequest(String razorpay_order_id, String razorpay_payment_id, String razorpay_signature) {}

  public record VerifyResponse(int balance) {}

  /** Verify a completed Checkout payment and grant credits. */
  @PostMapping("/verify")
  public ResponseEntity<?> verify(@AuthenticationPrincipal User user, @RequestBody VerifyRequest request) {
    if (isBlank(request.razorpay_order_id()) || isBlank(request.razorpay_payment_id()) || isBlank(request.razorpay_signature())) {
      throw new BadRequestException("razorpay_order_id, razorpay_payment_id and razorpay_signature are required");
    }

    boolean ok =
        razorpay.verifyPaymentSignature(request.razorpay_order_id(), request.razorpay_payment_id(), request.razorpay_signature());
    if (!ok) {
      paymentRepository.updateStatusIfCurrentlyStatus(request.razorpay_order_id(), PaymentStatus.CREATED, PaymentStatus.FAILED);
      return ResponseEntity.status(400).body(Map.of("error", "Payment signature verification failed."));
    }

    Payment payment = paymentRepository.findByRazorpayOrderId(request.razorpay_order_id()).orElse(null);
    if (payment == null || !payment.getUser().getId().equals(user.getId())) {
      return ResponseEntity.status(404).body(Map.of("error", "Order not found."));
    }

    CreditsService.FulfillResult result =
        creditsService.fulfillPayment(request.razorpay_order_id(), request.razorpay_payment_id(), request.razorpay_signature());
    if (result == null) {
      return ResponseEntity.status(404).body(Map.of("error", "Order not found."));
    }

    return ResponseEntity.ok(new VerifyResponse(creditsService.getBalance(user.getId())));
  }

  /**
   * Razorpay webhook backstop: grants credits on payment.captured in case the browser never
   * returned to /verify. Publicly reachable (no session, permitted in SecurityConfig) —
   * authenticated instead via the X-Razorpay-Signature HMAC header.
   */
  @PostMapping("/webhook")
  public ResponseEntity<Map<String, String>> webhook(
      @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature, @RequestBody String rawBody) {
    String sig = signature != null ? signature : "";
    if (!razorpay.verifyWebhookSignature(rawBody, sig)) {
      return ResponseEntity.status(400).body(Map.of("error", "Invalid webhook signature"));
    }
    try {
      JsonNode event = objectMapper.readTree(rawBody);
      if ("payment.captured".equals(text(event, "event"))) {
        JsonNode entity = event.path("payload").path("payment").path("entity");
        String orderId = text(entity, "order_id");
        String paymentId = text(entity, "id");
        if (orderId != null && paymentId != null) {
          creditsService.fulfillPayment(orderId, paymentId, null);
        }
      }
      return ResponseEntity.ok(Map.of("status", "ok"));
    } catch (Exception e) {
      log.error("Webhook handling failed: {}", e.getMessage());
      return ResponseEntity.ok(Map.of("status", "ignored"));
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  private static String nullIfBlank(String s) {
    return isBlank(s) ? null : s;
  }
}
