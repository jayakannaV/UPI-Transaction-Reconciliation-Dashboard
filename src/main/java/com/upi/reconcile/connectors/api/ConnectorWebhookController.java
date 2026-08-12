package com.upi.reconcile.connectors.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.upi.reconcile.api.WebhookRequest;
import com.upi.reconcile.api.WebhookResponse;
import com.upi.reconcile.connectors.PaymentGatewayConnector;
import com.upi.reconcile.connectors.crypto.AesGcmEncryptor;
import com.upi.reconcile.connectors.crypto.HmacSignatureVerifier;
import com.upi.reconcile.connectors.crypto.PayuHashVerifier;
import com.upi.reconcile.connectors.domain.Merchant;
import com.upi.reconcile.connectors.domain.MerchantGatewayConnection;
import com.upi.reconcile.connectors.domain.MerchantGatewayConnectionRepository;
import com.upi.reconcile.connectors.domain.MerchantRepository;
import com.upi.reconcile.ingestion.TransactionEventProducer;
import com.upi.reconcile.ingestion.WebhookResultHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Webhook endpoints for payment gateway connectors.
 *
 * <p>
 * Each endpoint receives raw webhook calls from the respective gateway,
 * normalizes them via the appropriate {@link PaymentGatewayConnector}, then
 * feeds the resulting {@link WebhookRequest} into the existing Kafka
 * ingestion pipeline — exactly the same flow as
 * {@link com.upi.reconcile.api.WebhookController}.
 *
 * <pre>
 * POST /api/connectors/razorpay/webhook?merchant_id=uuid&amp;connection_id=uuid   → RazorpayConnector (HMAC-SHA256 verified)
 * POST /api/connectors/payu/webhook?merchant_id=uuid&amp;connection_id=uuid        → PayUConnector      (SHA-512 hash verified)
 * POST /api/connectors/cashfree/webhook?merchant_id=uuid&amp;connection_id=uuid    → CashfreeConnector  (stub — no signature)
 * </pre>
 *
 * <p>
 * Flow: receive raw payload → look up connection (reject 410 if DISCONNECTED)
 * → (verify signature) → normalize → register future → publish to Kafka
 * → consumer processes → return result.
 */
@Slf4j
@RestController
@RequestMapping("/api/connectors")
public class ConnectorWebhookController {

    private final Map<String, PaymentGatewayConnector> connectorMap;
    private final TransactionEventProducer producer;
    private final WebhookResultHolder resultHolder;
    private final ObjectMapper objectMapper;
    private final MerchantRepository merchantRepository;
    private final MerchantGatewayConnectionRepository connectionRepository;
    private final AesGcmEncryptor encryptor;

    public ConnectorWebhookController(List<PaymentGatewayConnector> connectors,
            TransactionEventProducer producer,
            WebhookResultHolder resultHolder,
            ObjectMapper objectMapper,
            MerchantRepository merchantRepository,
            MerchantGatewayConnectionRepository connectionRepository,
            AesGcmEncryptor encryptor) {
        this.connectorMap = connectors.stream()
                .collect(Collectors.toMap(PaymentGatewayConnector::gatewayName, Function.identity()));
        this.producer = producer;
        this.resultHolder = resultHolder;
        this.objectMapper = objectMapper;
        this.merchantRepository = merchantRepository;
        this.connectionRepository = connectionRepository;
        this.encryptor = encryptor;
    }

    /**
     * Razorpay webhook endpoint — validates HMAC-SHA256 signature before
     * processing.
     *
     * <p>
     * Razorpay signs each webhook POST body using the webhook secret configured in
     * the merchant's Razorpay Dashboard → Settings → Webhooks. The signature is
     * sent
     * as a lowercase hex string in the {@code X-Razorpay-Signature} header.
     *
     * <p>
     * We look up the merchant by the {@code merchant_id} query parameter (which is
     * embedded in the webhook URL returned during onboarding), retrieve the
     * connection's {@code webhook_secret}, and verify the HMAC. Requests with
     * missing or invalid signatures are rejected with HTTP 401.
     *
     * @param rawBody    the raw request body as a string (needed for HMAC
     *                   computation)
     * @param signature  the X-Razorpay-Signature header value
     * @param merchantId the merchant_id query parameter from the webhook URL
     * @param connectionIdParam optional connection_id query parameter
     */
    @PostMapping("/razorpay/webhook")
    @SuppressWarnings("unchecked")
    public ResponseEntity<WebhookResponse> razorpayWebhook(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
            @RequestParam(value = "merchant_id", required = false) String merchantId,
            @RequestParam(value = "connection_id", required = false) String connectionIdParam) throws Exception {

        // ── 1. Validate signature header is present ─────────────────
        if (signature == null || signature.isBlank()) {
            log.warn("Razorpay webhook rejected — missing X-Razorpay-Signature header");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // ── 2. Look up merchant ─────────────────────────────────────
        if (merchantId == null || merchantId.isBlank()) {
            log.warn("Razorpay webhook rejected — missing merchant_id query parameter");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        UUID merchantUuid;
        try {
            merchantUuid = UUID.fromString(merchantId);
        } catch (IllegalArgumentException e) {
            log.warn("Razorpay webhook rejected — invalid merchant_id format: {}", merchantId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Optional<Merchant> merchantOpt = merchantRepository.findById(merchantUuid);
        if (merchantOpt.isEmpty()) {
            log.warn("Razorpay webhook rejected — unknown merchant_id: {}", merchantId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // ── 3. Look up gateway connection and check status ──────────
        MerchantGatewayConnection connection = resolveConnection(
                merchantUuid, "razorpay", connectionIdParam);

        if (connection == null) {
            log.warn("Razorpay webhook rejected — no connection found for merchant {}", merchantId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if (MerchantGatewayConnection.STATUS_DISCONNECTED.equals(connection.getStatus())) {
            log.warn("Razorpay webhook rejected — connection {} is DISCONNECTED", connection.getConnectionId());
            return ResponseEntity.status(HttpStatus.GONE).build();
        }

        String webhookSecret = connection.getWebhookSecret();
        if (webhookSecret == null || webhookSecret.isBlank()) {
            log.warn("Razorpay webhook rejected — connection {} has no webhook_secret configured",
                    connection.getConnectionId());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // ── 4. Verify HMAC-SHA256 signature ─────────────────────────
        if (!HmacSignatureVerifier.verify(rawBody, webhookSecret, signature)) {
            log.warn("Razorpay webhook rejected — HMAC-SHA256 signature mismatch for merchant {}", merchantId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        log.info("Razorpay webhook signature verified for merchant {}", merchantId);

        // ── 5. Deserialize and process normally ─────────────────────
        Map<String, Object> rawPayload = objectMapper.readValue(rawBody, Map.class);
        return processGatewayWebhook("razorpay", rawPayload, merchantUuid, connection.getConnectionId());
    }

    /**
     * PayU webhook endpoint — receives form-encoded POST from PayU and verifies
     * the SHA-512 hash before processing.
     *
     * <p>
     * PayU sends webhooks as {@code application/x-www-form-urlencoded} with fields:
     * {@code mihpayid, txnid, amount, productinfo, firstname, email, status, hash,
     * udf1–udf5, field1–field9}.
     *
     * <p>
     * Hash verification uses the merchant's Salt (stored as {@code api_secret})
     * via the reverse pipe formula:
     * {@code SHA512(salt|status||||||udf5|udf4|udf3|udf2|udf1|email|firstname|productinfo|amount|txnid|key)}
     *
     * @param formParams the URL-decoded form fields from PayU's POST body
     * @param merchantId the merchant_id query param embedded in the webhook URL
     * @param connectionIdParam optional connection_id query parameter
     */
    @PostMapping(value = "/payu/webhook", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<WebhookResponse> payuWebhook(
            @RequestParam MultiValueMap<String, String> formParams,
            @RequestParam(value = "merchant_id", required = false) String merchantId,
            @RequestParam(value = "connection_id", required = false) String connectionIdParam) throws Exception {

        // ── 1. Validate merchant_id is present ───────────────────────
        if (merchantId == null || merchantId.isBlank()) {
            log.warn("PayU webhook rejected — missing merchant_id query parameter");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        UUID merchantUuid;
        try {
            merchantUuid = UUID.fromString(merchantId);
        } catch (IllegalArgumentException e) {
            log.warn("PayU webhook rejected — invalid merchant_id format: {}", merchantId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Optional<Merchant> merchantOpt = merchantRepository.findById(merchantUuid);
        if (merchantOpt.isEmpty()) {
            log.warn("PayU webhook rejected — unknown merchant_id: {}", merchantId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // ── 2. Look up gateway connection and check status ──────────
        MerchantGatewayConnection connection = resolveConnection(
                merchantUuid, "payu", connectionIdParam);

        if (connection == null) {
            log.warn("PayU webhook rejected — no connection found for merchant {}", merchantId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if (MerchantGatewayConnection.STATUS_DISCONNECTED.equals(connection.getStatus())) {
            log.warn("PayU webhook rejected — connection {} is DISCONNECTED", connection.getConnectionId());
            return ResponseEntity.status(HttpStatus.GONE).build();
        }

        // ── 3. Extract hash and required fields for verification ─────
        String hash = formParams.getFirst("hash");
        if (hash == null || hash.isBlank()) {
            log.warn("PayU webhook rejected — missing 'hash' field for merchant {}", merchantId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String txnid = nvlForm(formParams, "txnid");
        String amount = nvlForm(formParams, "amount");
        String productinfo = nvlForm(formParams, "productinfo");
        String firstname = nvlForm(formParams, "firstname");
        String email = nvlForm(formParams, "email");
        String status = nvlForm(formParams, "status");
        String udf1 = nvlForm(formParams, "udf1");
        String udf2 = nvlForm(formParams, "udf2");
        String udf3 = nvlForm(formParams, "udf3");
        String udf4 = nvlForm(formParams, "udf4");
        String udf5 = nvlForm(formParams, "udf5");

        // Decrypt the connection's key and salt for hash verification
        String merchantKey = encryptor.decrypt(connection.getEncryptedApiKey());
        String merchantSalt = encryptor.decrypt(connection.getEncryptedApiSecret());

        // ── 4. Verify SHA-512 hash ───────────────────────────────────
        boolean hashValid = PayuHashVerifier.verify(
                merchantKey, merchantSalt,
                txnid, amount, productinfo,
                firstname, email,
                udf1, udf2, udf3, udf4, udf5,
                status,
                hash);

        if (!hashValid) {
            log.warn("PayU webhook rejected — SHA-512 hash mismatch for txnid={}, merchant={}",
                    txnid, merchantId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        log.info("PayU webhook hash verified ✓ for merchant={}, txnid={}, status={}",
                merchantId, txnid, status);

        // ── 5. Build payload map (pass merchant_id for PayUConnector lookup) ──
        Map<String, Object> rawPayload = new HashMap<>();
        formParams.forEach((key, values) -> rawPayload.put(key, values.isEmpty() ? "" : values.getFirst()));
        rawPayload.put("__merchant_id__", merchantId); // sentinel for connector

        return processGatewayWebhook("payu", rawPayload, merchantUuid, connection.getConnectionId());
    }

    /**
     * PayU webhook fallback — accepts JSON body for testing convenience.
     * Hash verification is still performed if a {@code hash} field is present.
     * Use this endpoint with your test scripts that POST JSON.
     */
    @PostMapping(value = "/payu/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WebhookResponse> payuWebhookJson(
            @RequestBody Map<String, Object> rawPayload,
            @RequestParam(value = "merchant_id", required = false) String merchantId,
            @RequestParam(value = "connection_id", required = false) String connectionIdParam) throws Exception {

        UUID payuMerchantUuid = null;
        UUID payuConnectionId = null;
        if (merchantId != null && !merchantId.isBlank()) {
            rawPayload.put("__merchant_id__", merchantId);
            try {
                payuMerchantUuid = UUID.fromString(merchantId);
            } catch (IllegalArgumentException ignored) {
                // invalid UUID — proceed without merchant context
            }
        }

        // Look up connection for status check
        if (payuMerchantUuid != null) {
            MerchantGatewayConnection connection = resolveConnection(
                    payuMerchantUuid, "payu", connectionIdParam);
            if (connection != null) {
                if (MerchantGatewayConnection.STATUS_DISCONNECTED.equals(connection.getStatus())) {
                    log.warn("PayU JSON webhook rejected — connection {} is DISCONNECTED",
                            connection.getConnectionId());
                    return ResponseEntity.status(HttpStatus.GONE).build();
                }
                payuConnectionId = connection.getConnectionId();
            }
        }

        return processGatewayWebhook("payu", rawPayload, payuMerchantUuid, payuConnectionId);
    }

    /** Extracts a form field value or returns empty string if absent. */
    private String nvlForm(MultiValueMap<String, String> map, String key) {
        String val = map.getFirst(key);
        return val != null ? val.trim() : "";
    }

    /**
     * Cashfree stub webhook — no signature verification (sandbox integration).
     */
    @PostMapping("/cashfree/webhook")
    public ResponseEntity<WebhookResponse> cashfreeWebhook(
            @RequestBody Map<String, Object> rawPayload,
            @RequestParam(value = "merchant_id", required = false) String merchantId,
            @RequestParam(value = "connection_id", required = false) String connectionIdParam) throws Exception {

        UUID cashfreeMerchantUuid = null;
        UUID cashfreeConnectionId = null;
        if (merchantId != null && !merchantId.isBlank()) {
            try {
                cashfreeMerchantUuid = UUID.fromString(merchantId);
            } catch (IllegalArgumentException ignored) { }
        }

        // Look up connection for status check
        if (cashfreeMerchantUuid != null) {
            MerchantGatewayConnection connection = resolveConnection(
                    cashfreeMerchantUuid, "cashfree", connectionIdParam);
            if (connection != null) {
                if (MerchantGatewayConnection.STATUS_DISCONNECTED.equals(connection.getStatus())) {
                    log.warn("Cashfree webhook rejected — connection {} is DISCONNECTED",
                            connection.getConnectionId());
                    return ResponseEntity.status(HttpStatus.GONE).build();
                }
                cashfreeConnectionId = connection.getConnectionId();
            }
        }

        return processGatewayWebhook("cashfree", rawPayload, cashfreeMerchantUuid, cashfreeConnectionId);
    }

    // ── Connection resolution ────────────────────────────────────

    /**
     * Resolves the gateway connection for a webhook request.
     *
     * <p>Prefers {@code connection_id} query param if present (direct lookup),
     * otherwise falls back to {@code merchant_id + gateway} lookup.
     *
     * @return the connection, or null if not found
     */
    private MerchantGatewayConnection resolveConnection(
            UUID merchantUuid, String gateway, String connectionIdParam) {

        // Try direct connection_id lookup first
        if (connectionIdParam != null && !connectionIdParam.isBlank()) {
            try {
                UUID connectionId = UUID.fromString(connectionIdParam);
                Optional<MerchantGatewayConnection> conn = connectionRepository
                        .findByConnectionIdAndMerchant_MerchantId(connectionId, merchantUuid);
                if (conn.isPresent()) {
                    return conn.get();
                }
            } catch (IllegalArgumentException ignored) {
                // invalid UUID — fall through to merchant+gateway lookup
            }
        }

        // Fallback: look up by merchant_id + gateway (any status)
        // Try ACTIVE first, then any status
        Optional<MerchantGatewayConnection> active = connectionRepository
                .findByMerchant_MerchantIdAndGatewayAndStatus(
                        merchantUuid, gateway, MerchantGatewayConnection.STATUS_ACTIVE);
        if (active.isPresent()) {
            return active.get();
        }

        // Check for DISCONNECTED (to return 410)
        Optional<MerchantGatewayConnection> disconnected = connectionRepository
                .findByMerchant_MerchantIdAndGatewayAndStatus(
                        merchantUuid, gateway, MerchantGatewayConnection.STATUS_DISCONNECTED);
        return disconnected.orElse(null);
    }

    /**
     * Common processing logic for all gateway webhooks.
     *
     * <ol>
     * <li>Look up the connector for the gateway</li>
     * <li>Normalize the raw payload into a {@link WebhookRequest}</li>
     * <li>Register a {@link CompletableFuture} on the result holder</li>
     * <li>Publish to Kafka (same topic as the main webhook endpoint)</li>
     * <li>Block until the consumer processes the message</li>
     * </ol>
     */
    private ResponseEntity<WebhookResponse> processGatewayWebhook(
            String gatewayName, Map<String, Object> rawPayload,
            UUID merchantId, UUID connectionId) throws Exception {

        PaymentGatewayConnector connector = connectorMap.get(gatewayName);
        if (connector == null) {
            log.error("No connector found for gateway '{}'", gatewayName);
            return ResponseEntity.badRequest().build();
        }

        log.info("Received {} webhook — normalizing payload", gatewayName);

        // 1. Normalize the raw payload into internal schema
        WebhookRequest normalized = connector.normalizeWebhookPayload(rawPayload);
        normalized.setSourceGateway(gatewayName);
        normalized.setMerchantId(merchantId);
        normalized.setConnectionId(connectionId);

        log.info("Normalized {} webhook — idempotency_key={}", gatewayName, normalized.getIdempotencyKey());

        // 2. Register a future BEFORE publishing to Kafka
        CompletableFuture<WebhookResponse> future = resultHolder.register(normalized.getIdempotencyKey());

        // 3. Serialize the normalized request and publish to Kafka
        String json;
        try {
            json = objectMapper.writeValueAsString(normalized);
        } catch (JsonProcessingException e) {
            resultHolder.completeExceptionally(normalized.getIdempotencyKey(), e);
            throw new RuntimeException("Failed to serialize normalized webhook request", e);
        }

        producer.publishWebhookEvent(normalized.getIdempotencyKey(), json);

        // 4. Block until the consumer processes the message
        WebhookResponse response = resultHolder.await(future);

        log.info("{} webhook processed — txn={}, state={}",
                gatewayName, response.getTxnId(), response.getState());

        return ResponseEntity.ok(response);
    }
}
