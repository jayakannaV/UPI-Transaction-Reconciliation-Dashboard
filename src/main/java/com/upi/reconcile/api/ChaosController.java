package com.upi.reconcile.api;

import com.upi.reconcile.domain.ChaosService;
import com.upi.reconcile.domain.Transaction;
import com.upi.reconcile.security.MerchantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Demo-only chaos control endpoints — NEVER active in a non-demo profile.
 *
 * <p>All endpoints require a valid JWT (the authenticated merchant's own
 * {@code merchant_id} is extracted from the security context). A merchant
 * can only trigger chaos actions against their own data — never another
 * merchant's transactions.
 *
 * <p>The {@code @Profile("demo")} annotation ensures this controller bean
 * is not even registered by Spring unless {@code SPRING_PROFILES_ACTIVE=demo}
 * is set. This is a compile-time guarantee that chaos endpoints can never
 * leak into production.
 *
 * <h3>Available endpoints:</h3>
 * <pre>
 * POST /api/chaos/fire-duplicate/{txn_id}        — re-send same idempotency key
 * POST /api/chaos/create-deemed-approved          — create DEEMED_APPROVED transaction
 * POST /api/chaos/force-breach/{txn_id}           — set TAT deadline to near-future
 * POST /api/chaos/trigger-anomaly/{bank_id}       — fire burst of TD events
 * POST /api/chaos/simulate-razorpay-payment       — run real Razorpay connector path
 * GET  /api/chaos/seed-demo-data                  — seed mixed batch for dashboard
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/chaos")
@Profile("demo")
@RequiredArgsConstructor
public class ChaosController {

    private final ChaosService chaosService;

    // ── 1. Fire Duplicate ────────────────────────────────────────────────

    /**
     * Re-sends the same idempotency_key as an existing transaction belonging
     * to the current merchant. Should hit the existing dedup path and confirm
     * no new transaction is created.
     */
    @PostMapping("/fire-duplicate/{txnId}")
    public ResponseEntity<?> fireDuplicate(@PathVariable UUID txnId) {
        UUID merchantId = MerchantContextHolder.currentMerchantId();
        try {
            WebhookResponse response = chaosService.fireDuplicate(txnId, merchantId);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Chaos fire-duplicate failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to fire duplicate: " + e.getMessage()));
        }
    }

    // ── 2. Create Deemed Approved ────────────────────────────────────────

    /**
     * Creates a new transaction that deliberately skips online confirmation
     * and enters DEEMED_APPROVED, same as the natural flow would.
     */
    @PostMapping("/create-deemed-approved")
    public ResponseEntity<?> createDeemedApproved() {
        UUID merchantId = MerchantContextHolder.currentMerchantId();
        try {
            Transaction txn = chaosService.createDeemedApproved(merchantId);
            return ResponseEntity.ok(txnToMap(txn));
        } catch (Exception e) {
            log.error("Chaos create-deemed-approved failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to create deemed-approved: " + e.getMessage()));
        }
    }

    // ── 3. Force Breach ──────────────────────────────────────────────────

    /**
     * Takes an existing PENDING_RECONCILIATION transaction and sets its
     * tat_deadline to a few seconds in the future, so the natural scheduler
     * tick breaches it quickly for demo purposes.
     */
    @PostMapping("/force-breach/{txnId}")
    public ResponseEntity<?> forceBreach(@PathVariable UUID txnId) {
        UUID merchantId = MerchantContextHolder.currentMerchantId();
        try {
            Transaction txn = chaosService.forceBreach(txnId, merchantId);
            return ResponseEntity.ok(txnToMap(txn));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Chaos force-breach failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to force breach: " + e.getMessage()));
        }
    }

    // ── 4. Trigger Anomaly ───────────────────────────────────────────────

    /**
     * Fires a burst of TECHNICAL_DECLINED events against the given bank_id
     * for the current merchant's transactions, enough to cross the anomaly
     * detector's threshold.
     */
    @PostMapping("/trigger-anomaly/{bankId}")
    public ResponseEntity<?> triggerAnomaly(@PathVariable UUID bankId) {
        UUID merchantId = MerchantContextHolder.currentMerchantId();
        try {
            List<Transaction> created = chaosService.triggerAnomaly(bankId, merchantId);
            List<Map<String, Object>> result = created.stream()
                    .map(this::txnToMap)
                    .collect(Collectors.toList());
            return ResponseEntity.ok(Map.of(
                    "message", "Triggered " + created.size() + " TECHNICAL_DECLINED events",
                    "bankId", bankId,
                    "transactions", result));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Chaos trigger-anomaly failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to trigger anomaly: " + e.getMessage()));
        }
    }

    // ── 5. Simulate Razorpay Payment ─────────────────────────────────────

    /**
     * If the current merchant has an ACTIVE Razorpay connection, builds a
     * realistic Razorpay-shaped test payload and runs it through the REAL
     * RazorpayConnector path.
     */
    @PostMapping("/simulate-razorpay-payment")
    public ResponseEntity<?> simulateRazorpayPayment() {
        UUID merchantId = MerchantContextHolder.currentMerchantId();
        try {
            WebhookResponse response = chaosService.simulateRazorpayPayment(merchantId);
            if (response == null) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("error",
                                "Connect Razorpay first to use this action"));
            }
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Chaos simulate-razorpay-payment failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to simulate Razorpay payment: " + e.getMessage()));
        }
    }

    // ── 6. Seed Demo Data ────────────────────────────────────────────────

    /**
     * Convenience endpoint that creates a small realistic mixed batch so the
     * dashboard has visible variety at the start of a demo.
     */
    @GetMapping("/seed-demo-data")
    public ResponseEntity<?> seedDemoData() {
        UUID merchantId = MerchantContextHolder.currentMerchantId();
        try {
            List<Transaction> created = chaosService.seedDemoData(merchantId);
            List<Map<String, Object>> result = created.stream()
                    .map(this::txnToMap)
                    .collect(Collectors.toList());
            return ResponseEntity.ok(Map.of(
                    "message", "Seeded " + created.size() + " demo transactions",
                    "transactions", result));
        } catch (Exception e) {
            log.error("Chaos seed-demo-data failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to seed demo data: " + e.getMessage()));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Converts a Transaction entity to a simple map for JSON response.
     */
    private Map<String, Object> txnToMap(Transaction txn) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("txnId", txn.getTxnId());
        map.put("state", txn.getState().name());
        map.put("idempotencyKey", txn.getIdempotencyKey());
        map.put("amountInr", txn.getAmountInr());
        map.put("sourceGateway", txn.getSourceGateway());
        map.put("createdAt", txn.getCreatedAt());
        if (txn.getTatDeadline() != null) {
            map.put("tatDeadline", txn.getTatDeadline());
        }
        if (txn.getPenaltyAmountInr() != null) {
            map.put("penaltyAmountInr", txn.getPenaltyAmountInr());
        }
        return map;
    }
}
