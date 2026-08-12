package com.upi.reconcile.connectors.api;

import com.upi.reconcile.connectors.crypto.AesGcmEncryptor;
import com.upi.reconcile.connectors.domain.Merchant;
import com.upi.reconcile.connectors.domain.MerchantGatewayConnection;
import com.upi.reconcile.connectors.domain.MerchantGatewayConnectionRepository;
import com.upi.reconcile.connectors.domain.MerchantRepository;
import com.upi.reconcile.security.MerchantContextHolder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Gateway connection lifecycle endpoints.
 *
 * <pre>
 * GET    /api/connections                              → list all connections
 * POST   /api/connections                              → create a new ACTIVE connection (409 if duplicate)
 * DELETE /api/connections/{connection_id}               → disconnect (ACTIVE → DISCONNECTED)
 * POST   /api/connections/{connection_id}/reconnect     → re-activate with fresh credentials
 * </pre>
 *
 * <p>All endpoints require JWT authentication.
 */
@Slf4j
@RestController
@RequestMapping("/api/connections")
@RequiredArgsConstructor
public class ConnectionController {

    private static final Set<String> SUPPORTED_GATEWAYS = Set.of("razorpay", "payu", "cashfree");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final MerchantRepository merchantRepository;
    private final MerchantGatewayConnectionRepository connectionRepository;
    private final AesGcmEncryptor encryptor;

    // ── GET /api/connections ──────────────────────────────────────

    /**
     * List all gateway connections for the authenticated merchant.
     */
    @GetMapping
    public ResponseEntity<List<ConnectionDto>> listConnections() {
        UUID merchantId = MerchantContextHolder.currentMerchantId();

        List<ConnectionDto> connections = connectionRepository
                .findByMerchant_MerchantId(merchantId)
                .stream()
                .map(c -> ConnectionDto.builder()
                        .connectionId(c.getConnectionId())
                        .gateway(c.getGateway())
                        .status(c.getStatus())
                        .connectedAt(c.getConnectedAt())
                        .disconnectedAt(c.getDisconnectedAt())
                        .build())
                .collect(Collectors.toList());

        return ResponseEntity.ok(connections);
    }

    // ── POST /api/connections ─────────────────────────────────────

    /**
     * Create a new ACTIVE gateway connection.
     *
     * <p>Returns 409 Conflict if an ACTIVE connection to the same gateway
     * already exists for this merchant.
     */
    @PostMapping
    public ResponseEntity<?> createConnection(
            @Valid @RequestBody MerchantConnectRequest request) {

        String gateway = request.getGateway().toLowerCase().trim();
        if (!SUPPORTED_GATEWAYS.contains(gateway)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Unsupported gateway: " + gateway));
        }

        UUID merchantId = MerchantContextHolder.currentMerchantId();
        Merchant merchant = merchantRepository.findById(merchantId).orElse(null);
        if (merchant == null) {
            return ResponseEntity.notFound().build();
        }

        // Check for existing ACTIVE connection to this gateway
        Optional<MerchantGatewayConnection> existing = connectionRepository
                .findByMerchant_MerchantIdAndGatewayAndStatus(
                        merchantId, gateway, MerchantGatewayConnection.STATUS_ACTIVE);

        if (existing.isPresent()) {
            log.warn("409 Conflict — merchant {} already has ACTIVE {} connection (id={})",
                    merchantId, gateway, existing.get().getConnectionId());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of(
                            "error", "An active connection to " + gateway + " already exists",
                            "existing_connection_id", existing.get().getConnectionId()));
        }

        // Encrypt credentials
        String encryptedApiKey = encryptor.encrypt(request.getApiKey());
        String encryptedApiSecret = encryptor.encrypt(request.getApiSecret());

        // Generate webhook secret
        String webhookSecret = generateWebhookSecret();

        // Create connection
        UUID connectionId = UUID.randomUUID();
        MerchantGatewayConnection connection = MerchantGatewayConnection.builder()
                .connectionId(connectionId)
                .merchant(merchant)
                .gateway(gateway)
                .encryptedApiKey(encryptedApiKey)
                .encryptedApiSecret(encryptedApiSecret)
                .webhookSecret(webhookSecret)
                .status(MerchantGatewayConnection.STATUS_ACTIVE)
                .connectedAt(OffsetDateTime.now())
                .build();
        connectionRepository.save(connection);

        String webhookUrl = String.format(
                "/api/connectors/%s/webhook?merchant_id=%s&connection_id=%s",
                gateway, merchantId, connectionId);

        log.info("Gateway connection created — connection_id={}, merchant_id={}, gateway={}",
                connectionId, merchantId, gateway);

        return ResponseEntity.status(HttpStatus.CREATED).body(
                ConnectionCreateResponse.builder()
                        .connectionId(connectionId)
                        .webhookUrl(webhookUrl)
                        .webhookSecret(webhookSecret)
                        .build());
    }

    // ── DELETE /api/connections/{connection_id} ───────────────────

    /**
     * Disconnect a gateway connection — sets status to DISCONNECTED.
     *
     * <p>Incoming webhooks for this connection will be rejected with 410 Gone
     * at the connector layer.
     */
    @DeleteMapping("/{connectionId}")
    public ResponseEntity<?> disconnectConnection(
            @PathVariable UUID connectionId) {

        UUID merchantId = MerchantContextHolder.currentMerchantId();

        Optional<MerchantGatewayConnection> opt = connectionRepository
                .findByConnectionIdAndMerchant_MerchantId(connectionId, merchantId);

        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        MerchantGatewayConnection connection = opt.get();

        if (MerchantGatewayConnection.STATUS_DISCONNECTED.equals(connection.getStatus())) {
            return ResponseEntity.ok(Map.of(
                    "message", "Connection is already disconnected",
                    "connection_id", connectionId));
        }

        connection.setStatus(MerchantGatewayConnection.STATUS_DISCONNECTED);
        connection.setDisconnectedAt(OffsetDateTime.now());
        connectionRepository.save(connection);

        log.info("Gateway connection disconnected — connection_id={}, merchant_id={}, gateway={}",
                connectionId, merchantId, connection.getGateway());

        return ResponseEntity.ok(Map.of(
                "message", "Connection disconnected",
                "connection_id", connectionId,
                "gateway", connection.getGateway()));
    }

    // ── POST /api/connections/{connection_id}/reconnect ──────────

    /**
     * Re-activate a DISCONNECTED connection with fresh credentials.
     *
     * <p>Generates a new webhook secret for security (in case the old one
     * was compromised or the merchant rotated their gateway API keys).
     */
    @PostMapping("/{connectionId}/reconnect")
    public ResponseEntity<?> reconnectConnection(
            @PathVariable UUID connectionId,
            @Valid @RequestBody MerchantConnectRequest request) {

        UUID merchantId = MerchantContextHolder.currentMerchantId();

        Optional<MerchantGatewayConnection> opt = connectionRepository
                .findByConnectionIdAndMerchant_MerchantId(connectionId, merchantId);

        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        MerchantGatewayConnection connection = opt.get();

        // Check if there's already another ACTIVE connection for this gateway
        // (edge case: merchant disconnected A, created B, now tries to reconnect A)
        if (MerchantGatewayConnection.STATUS_DISCONNECTED.equals(connection.getStatus())) {
            Optional<MerchantGatewayConnection> activeConflict = connectionRepository
                    .findByMerchant_MerchantIdAndGatewayAndStatus(
                            merchantId, connection.getGateway(),
                            MerchantGatewayConnection.STATUS_ACTIVE);
            if (activeConflict.isPresent()) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of(
                                "error", "Another active connection to "
                                        + connection.getGateway() + " already exists",
                                "existing_connection_id", activeConflict.get().getConnectionId()));
            }
        }

        // Update credentials and re-activate
        connection.setEncryptedApiKey(encryptor.encrypt(request.getApiKey()));
        connection.setEncryptedApiSecret(encryptor.encrypt(request.getApiSecret()));

        String newWebhookSecret = generateWebhookSecret();
        connection.setWebhookSecret(newWebhookSecret);
        connection.setStatus(MerchantGatewayConnection.STATUS_ACTIVE);
        connection.setDisconnectedAt(null);
        connectionRepository.save(connection);

        String webhookUrl = String.format(
                "/api/connectors/%s/webhook?merchant_id=%s&connection_id=%s",
                connection.getGateway(), merchantId, connectionId);

        log.info("Gateway connection reconnected — connection_id={}, merchant_id={}, gateway={}",
                connectionId, merchantId, connection.getGateway());

        return ResponseEntity.ok(
                ConnectionCreateResponse.builder()
                        .connectionId(connectionId)
                        .webhookUrl(webhookUrl)
                        .webhookSecret(newWebhookSecret)
                        .build());
    }

    // ── Helpers ───────────────────────────────────────────────────

    /** Generates a cryptographically secure 32-byte hex-encoded webhook secret. */
    private String generateWebhookSecret() {
        byte[] secretBytes = new byte[32];
        SECURE_RANDOM.nextBytes(secretBytes);
        return HexFormat.of().formatHex(secretBytes);
    }
}
