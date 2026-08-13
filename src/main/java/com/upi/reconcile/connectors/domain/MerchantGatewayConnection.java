package com.upi.reconcile.connectors.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Represents a single gateway connection for a merchant.
 *
 * <p>
 * A merchant may have zero, one, or multiple connections across different
 * gateways (razorpay, payu, cashfree). Each connection stores AES-256-GCM
 * encrypted API credentials and an HMAC webhook secret.
 *
 * <p>
 * Lifecycle states:
 * <ul>
 *   <li>{@code ACTIVE} — credentials are live, webhooks are accepted</li>
 *   <li>{@code DISCONNECTED} — webhooks are rejected (410 Gone), credentials
 *       are retained for audit but not used</li>
 * </ul>
 *
 * <p>
 * A partial unique index on {@code (merchant_id, gateway) WHERE status = 'ACTIVE'}
 * ensures at most one active connection per gateway per merchant.
 */
@Entity
@Table(name = "merchant_gateway_connections")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MerchantGatewayConnection {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISCONNECTED = "DISCONNECTED";

    @Id
    @Column(name = "connection_id")
    private UUID connectionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merchant_id", nullable = false)
    private Merchant merchant;

    @Column(nullable = false)
    private String gateway;

    @Column(name = "encrypted_api_key", nullable = false)
    private String encryptedApiKey;

    @Column(name = "encrypted_api_secret", nullable = false)
    private String encryptedApiSecret;

    @Column(name = "webhook_secret")
    private String webhookSecret;

    @Column(nullable = false)
    private String status;

    @Column(name = "connected_at", nullable = false)
    private OffsetDateTime connectedAt;

    @Column(name = "disconnected_at")
    private OffsetDateTime disconnectedAt;
}
