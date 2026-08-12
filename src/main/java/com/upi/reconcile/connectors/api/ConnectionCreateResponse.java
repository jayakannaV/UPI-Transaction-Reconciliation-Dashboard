package com.upi.reconcile.connectors.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Response DTO for {@code POST /api/connections} and
 * {@code POST /api/connections/{id}/reconnect}.
 *
 * <pre>
 * {
 *   "connection_id": "uuid",
 *   "webhook_url": "/api/connectors/razorpay/webhook?merchant_id=uuid&amp;connection_id=uuid",
 *   "webhook_secret": "hex-encoded-32-byte-random-secret"
 * }
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConnectionCreateResponse {

    private UUID connectionId;
    private String webhookUrl;

    /**
     * One-time display: the HMAC-SHA256 signing secret the merchant must paste
     * into their gateway's webhook configuration. Not stored in plaintext
     * anywhere after this response — the merchant must save it now.
     */
    private String webhookSecret;
}
