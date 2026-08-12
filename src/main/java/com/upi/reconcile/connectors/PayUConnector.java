package com.upi.reconcile.connectors;

import com.upi.reconcile.api.WebhookRequest;
import com.upi.reconcile.connectors.crypto.AesGcmEncryptor;
import com.upi.reconcile.connectors.crypto.PayuHashVerifier;
import com.upi.reconcile.connectors.domain.Merchant;
import com.upi.reconcile.connectors.domain.MerchantRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Real PayU webhook connector — parses the actual PayU form-POST webhook
 * payload and verifies the SHA-512 hash before processing.
 *
 * <h3>PayU webhook payload (form-encoded, key fields):</h3>
 * <pre>
 * mihpayid   — PayU's unique transaction ID
 * txnid      — your system's transaction ID (used as idempotency key)
 * amount     — transaction amount (decimal string, e.g. "100.00")
 * productinfo — product description
 * firstname  — customer first name
 * email      — customer email
 * status     — "success" | "failure" | "pending"
 * hash       — SHA-512 verification hash
 * udf1–udf5  — optional user-defined fields
 * field1–field9 — optional additional fields
 * </pre>
 *
 * <h3>Hash verification formula (reverse of request):</h3>
 * <pre>
 * SHA512(salt|status||||||udf5|udf4|udf3|udf2|udf1|email|firstname|productinfo|amount|txnid|key)
 * </pre>
 *
 * <p>The merchant's {@code api_key} = PayU Merchant Key.
 * The merchant's {@code api_secret} = PayU Merchant Salt (used in hash verification).
 *
 * @see <a href="https://devguide.payu.in/api/webhooks/">PayU Webhook Documentation</a>
 */
@Slf4j
@Component
public class PayUConnector implements PaymentGatewayConnector {

    private final MerchantRepository merchantRepository;
    private final AesGcmEncryptor encryptor;
    private final UUID defaultRemitterBankId;
    private final UUID defaultBeneficiaryBankId;

    public PayUConnector(
            MerchantRepository merchantRepository,
            AesGcmEncryptor encryptor,
            @Value("${app.connectors.default-remitter-bank-id}") String remitterBankId,
            @Value("${app.connectors.default-beneficiary-bank-id}") String beneficiaryBankId) {
        this.merchantRepository = merchantRepository;
        this.encryptor = encryptor;
        this.defaultRemitterBankId = UUID.fromString(remitterBankId);
        this.defaultBeneficiaryBankId = UUID.fromString(beneficiaryBankId);
    }

    @Override
    public String gatewayName() {
        return "payu";
    }

    /**
     * Normalizes a real PayU webhook form-POST payload.
     *
     * <p>The rawPayload map is pre-populated by the webhook controller from the
     * form-encoded body (each form field becomes a map entry). The merchant_id
     * is passed in the map under the key {@code "__merchant_id__"} by the controller
     * so this connector can look up the merchant's salt for hash verification.
     *
     * @throws IllegalArgumentException if any required field is missing
     * @throws SecurityException        if the SHA-512 hash verification fails
     */
    @Override
    public WebhookRequest normalizeWebhookPayload(Map<String, Object> rawPayload) {

        // ── 1. Extract required fields ───────────────────────────────
        String txnid      = require(rawPayload, "txnid");
        String amount     = require(rawPayload, "amount");
        String status     = require(rawPayload, "status");
        String hash       = require(rawPayload, "hash");
        String mihpayid   = require(rawPayload, "mihpayid");

        // Optional fields (empty string if absent — required in hash formula)
        String productinfo = nvl(rawPayload, "productinfo");
        String firstname   = nvl(rawPayload, "firstname");
        String email       = nvl(rawPayload, "email");
        String udf1        = nvl(rawPayload, "udf1");
        String udf2        = nvl(rawPayload, "udf2");
        String udf3        = nvl(rawPayload, "udf3");
        String udf4        = nvl(rawPayload, "udf4");
        String udf5        = nvl(rawPayload, "udf5");

        // ── 2. Look up the merchant to get key + salt ────────────────
        // The controller passes merchant_id in the map under a reserved key
        String merchantIdStr = (String) rawPayload.get("__merchant_id__");
        if (merchantIdStr == null || merchantIdStr.isBlank()) {
            throw new SecurityException("Missing __merchant_id__ context — cannot verify PayU hash");
        }

        Merchant merchant = merchantRepository.findById(UUID.fromString(merchantIdStr))
                .orElseThrow(() -> new SecurityException(
                        "Unknown merchant_id for PayU hash verification: " + merchantIdStr));

        String merchantKey  = encryptor.decrypt(merchant.getEncryptedApiKey());
        String merchantSalt = encryptor.decrypt(merchant.getEncryptedApiSecret());

        // ── 3. Verify SHA-512 hash ───────────────────────────────────
        boolean hashValid = PayuHashVerifier.verify(
                merchantKey, merchantSalt,
                txnid, amount, productinfo,
                firstname, email,
                udf1, udf2, udf3, udf4, udf5,
                status,
                hash);

        if (!hashValid) {
            log.warn("PayU webhook rejected — SHA-512 hash mismatch for txnid={}, merchant={}", txnid, merchantIdStr);
            throw new SecurityException(
                    "PayU SHA-512 hash verification failed for txnid=" + txnid);
        }

        log.info("PayU hash verified ✓ — mihpayid={}, txnid={}, amount={}, status={}",
                mihpayid, txnid, amount, status);

        // ── 4. Map status to decline code ───────────────────────────
        String declineCode = mapDeclineCode(status, rawPayload);

        // ── 5. Convert amount string to BigDecimal ───────────────────
        BigDecimal amountInr;
        try {
            amountInr = new BigDecimal(amount);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid 'amount' value in PayU payload: " + amount);
        }

        // Use mihpayid as idempotency key (PayU's own unique ID — guaranteed unique per transaction)
        return WebhookRequest.builder()
                .idempotencyKey("payu-" + mihpayid)
                .remitterBankId(defaultRemitterBankId)
                .beneficiaryBankId(defaultBeneficiaryBankId)
                .amountInr(amountInr)
                .orderReference(txnid)
                .declineCode(declineCode)
                .build();
    }

    /**
     * Maps PayU status → internal decline code.
     *
     * <ul>
     *   <li>{@code success}  → null (no decline — MATCH_FOUND → SUCCESS flow)</li>
     *   <li>{@code failure}  → field1 error code if present, else MISSING_EXCEPTION_CODE</li>
     *   <li>{@code pending}  → NO_CONFIRMATION (T+1 TAT clock starts)</li>
     *   <li>other            → MISSING_EXCEPTION_CODE (treated as technical decline)</li>
     * </ul>
     *
     * <p>PayU populates {@code field1} with the bank error code on failures.
     */
    private String mapDeclineCode(String status, Map<String, Object> rawPayload) {
        return switch (status.toLowerCase()) {
            case "success", "successful" -> null; // Capture succeeded — no decline
            case "failure", "failed"     -> {
                // field1 contains the error code returned by the bank/acquirer
                String errorCode = nvl(rawPayload, "field1");
                if (!errorCode.isBlank()) {
                    log.info("PayU failure — bank error code (field1): {}", errorCode);
                    yield mapBankErrorCode(errorCode);
                }
                // Fall back to 'error_Message' if field1 is absent
                String errorMsg = nvl(rawPayload, "error_Message");
                if (!errorMsg.isBlank()) {
                    log.info("PayU failure — error_Message: {}", errorMsg);
                }
                yield "MISSING_EXCEPTION_CODE";
            }
            case "pending"  -> {
                // Payment is in limbo — treat as NO_CONFIRMATION (stuck payment)
                log.info("PayU payment pending — treating as NO_CONFIRMATION");
                yield "NO_CONFIRMATION";
            }
            case "refund", "refunded" -> {
                log.info("PayU payment refund event received");
                yield "REFUND_ISSUED";
            }
            case "dispute" -> {
                log.info("PayU payment dispute event received");
                yield "CUSTOMER_DISPUTE";
            }
            default -> {
                log.warn("Unknown PayU status '{}' — treating as technical decline", status);
                yield "MISSING_EXCEPTION_CODE";
            }
        };
    }

    /**
     * Maps PayU/bank error codes to internal decline code categories.
     *
     * <p>PayU's {@code field1} contains raw bank/acquirer error codes.
     * Common codes are mapped to our two categories:
     * <ul>
     *   <li>BD (Business Decline): user error — wrong PIN, insufficient funds</li>
     *   <li>TD (Technical Decline): infra/network error — bank unavailable, timeout</li>
     * </ul>
     */
    private String mapBankErrorCode(String code) {
        // Known BD codes (user/business declines)
        return switch (code.toUpperCase()) {
            case "E000", "E001", "U002"              -> "BAD_PIN";       // Wrong PIN / Auth failed
            case "E002"                               -> "BAD_PIN";       // Insufficient funds
            case "U010", "U011", "U013"               -> "BAD_PIN";       // Card expired / blocked
            // Known TD codes (technical / network declines)
            case "E003", "E004", "E005"              -> "MALFORMED_BANK_ID"; // Bank unavailable
            case "U001", "U005", "U006", "U009"      -> "MALFORMED_BANK_ID"; // Network/timeout
            case "E501", "E502", "E503"              -> "MALFORMED_BANK_ID"; // Acquirer error
            default -> {
                log.warn("Unknown PayU bank error code '{}' — passing through as-is", code);
                yield code; // Consumer treats unknown codes as TD
            }
        };
    }

    private String require(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null || val.toString().isBlank()) {
            throw new IllegalArgumentException("Missing required field '" + key + "' in PayU webhook payload");
        }
        return val.toString().trim();
    }

    private String nvl(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString().trim() : "";
    }
}
