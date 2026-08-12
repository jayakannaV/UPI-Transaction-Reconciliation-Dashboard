package com.upi.reconcile.connectors.api;

import com.upi.reconcile.connectors.crypto.AesGcmEncryptor;
import com.upi.reconcile.connectors.domain.Merchant;
import com.upi.reconcile.connectors.domain.MerchantRepository;
import com.upi.reconcile.domain.ProvisionalRefundRepository;
import com.upi.reconcile.domain.RecoveryStatus;
import com.upi.reconcile.security.MerchantContextHolder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Merchant onboarding endpoint.
 *
 * <pre>
 * POST /api/merchants/connect
 *   body: { gateway, api_key, api_secret, name? }
 *   → 200 { merchant_id, webhook_url, webhook_secret }
 * </pre>
 *
 * <p>
 * Requires JWT authentication. Updates the authenticated merchant's gateway
 * credentials rather than creating a new merchant row.
 * <p>
 * A cryptographically secure 32-byte webhook secret is auto-generated at
 * connection time, stored on the Merchant entity, and returned once in the
 * response. The merchant must copy this secret into their gateway's webhook
 * configuration form for HMAC-SHA256 signature verification.
 * <p>
 * Encrypts the merchant's API key and secret using AES-256-GCM before
 * persisting to the {@code merchants} table, then returns a webhook URL
 * the merchant should register with their payment gateway dashboard.
 */
@Slf4j
@RestController
@RequestMapping("/api/merchants")
@RequiredArgsConstructor
public class MerchantController {

        private static final Set<String> SUPPORTED_GATEWAYS = Set.of("razorpay", "payu", "cashfree");
        private static final SecureRandom SECURE_RANDOM = new SecureRandom();

        private final MerchantRepository merchantRepository;
        private final AesGcmEncryptor encryptor;
        private final ProvisionalRefundRepository provisionalRefundRepository;

        @PostMapping("/connect")
        public ResponseEntity<MerchantConnectResponse> connectMerchant(
                        @Valid @RequestBody MerchantConnectRequest request) {

                String gateway = request.getGateway().toLowerCase().trim();
                if (!SUPPORTED_GATEWAYS.contains(gateway)) {
                        return ResponseEntity.badRequest().build();
                }

                // Get the authenticated merchant from JWT
                UUID merchantId = MerchantContextHolder.currentMerchantId();
                Merchant merchant = merchantRepository.findById(merchantId).orElse(null);
                if (merchant == null) {
                        return ResponseEntity.notFound().build();
                }

                // Encrypt credentials before storing
                String encryptedApiKey = encryptor.encrypt(request.getApiKey());
                String encryptedApiSecret = encryptor.encrypt(request.getApiSecret());

                // Generate a cryptographically secure 32-byte webhook secret
                byte[] secretBytes = new byte[32];
                SECURE_RANDOM.nextBytes(secretBytes);
                String webhookSecret = HexFormat.of().formatHex(secretBytes);

                // Update the existing merchant's gateway credentials
                if (request.getName() != null && !request.getName().isBlank()) {
                        merchant.setName(request.getName());
                }
                merchant.setConnectedGateway(gateway);
                merchant.setEncryptedApiKey(encryptedApiKey);
                merchant.setEncryptedApiSecret(encryptedApiSecret);
                merchant.setWebhookSecret(webhookSecret);
                merchantRepository.save(merchant);

                String webhookUrl = String.format("/api/connectors/%s/webhook?merchant_id=%s",
                                gateway, merchantId);

                log.info("Merchant connected — id={}, gateway={}, webhook_url={}",
                                merchantId, gateway, webhookUrl);

                MerchantConnectResponse response = MerchantConnectResponse.builder()
                                .merchantId(merchantId)
                                .webhookUrl(webhookUrl)
                                .webhookSecret(webhookSecret)
                                .build();

                return ResponseEntity.ok(response);
        }

        /**
         * Returns the gateway connected by the current authenticated merchant.
         *
         * <pre>
         * GET /api/merchants/connected-gateways
         *   → 200 ["razorpay"]
         * </pre>
         */
        @GetMapping("/connected-gateways")
        public ResponseEntity<List<String>> getConnectedGateways() {
                UUID merchantId = MerchantContextHolder.currentMerchantId();
                Merchant merchant = merchantRepository.findById(merchantId).orElse(null);

                if (merchant == null || merchant.getConnectedGateway() == null
                                || merchant.getConnectedGateway().isBlank()) {
                        return ResponseEntity.ok(List.of());
                }

                return ResponseEntity.ok(List.of(merchant.getConnectedGateway()));
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
