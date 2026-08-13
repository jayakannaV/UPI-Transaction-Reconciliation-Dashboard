package com.upi.reconcile.connectors.api;

import com.upi.reconcile.connectors.domain.MerchantGatewayConnection;
import com.upi.reconcile.connectors.domain.MerchantGatewayConnectionRepository;
import com.upi.reconcile.domain.ProvisionalRefundRepository;
import com.upi.reconcile.domain.RecoveryStatus;
import com.upi.reconcile.security.MerchantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Merchant information and summary endpoints.
 *
 * <pre>
 * GET /api/merchants/connected-gateways   → list active gateways
 * GET /api/merchants/provisional-summary  → pending recovery totals
 * </pre>
 *
 * <p>
 * Gateway connection lifecycle (connect/disconnect/reconnect) has moved
 * to {@link ConnectionController} at {@code /api/connections}.
 */
@Slf4j
@RestController
@RequestMapping("/api/merchants")
@RequiredArgsConstructor
public class MerchantController {

        private final MerchantGatewayConnectionRepository connectionRepository;
        private final ProvisionalRefundRepository provisionalRefundRepository;

        /**
         * Returns the gateways actively connected by the current merchant.
         *
         * <pre>
         * GET /api/merchants/connected-gateways
         *   → 200 ["razorpay", "payu"]
         * </pre>
         */
        @GetMapping("/connected-gateways")
        public ResponseEntity<List<String>> getConnectedGateways() {
                UUID merchantId = MerchantContextHolder.currentMerchantId();

                List<String> gateways = connectionRepository
                                .findByMerchant_MerchantId(merchantId)
                                .stream()
                                .filter(c -> MerchantGatewayConnection.STATUS_ACTIVE.equals(c.getStatus()))
                                .map(MerchantGatewayConnection::getGateway)
                                .collect(Collectors.toList());

                return ResponseEntity.ok(gateways);
        }

        /**
         * Returns the running total of provisional refunds still awaiting
         * bank recovery ({@code PENDING_FROM_BANK}).
         *
         * <pre>
         * GET /api/merchants/provisional-summary
         *   → 200 { "total_pending_recovery": 12500.00, "count": 5 }
         * </pre>
         */
        @GetMapping("/provisional-summary")
        public ResponseEntity<Map<String, Object>> getProvisionalSummary() {
                log.info("GET /api/merchants/provisional-summary");

                BigDecimal totalPending = provisionalRefundRepository
                                .sumAmountByRecoveryStatus(RecoveryStatus.PENDING_FROM_BANK);
                long count = provisionalRefundRepository
                                .countByRecoveryStatus(RecoveryStatus.PENDING_FROM_BANK);

                return ResponseEntity.ok(Map.of(
                                "total_pending_recovery", totalPending,
                                "count", count));
        }
}
