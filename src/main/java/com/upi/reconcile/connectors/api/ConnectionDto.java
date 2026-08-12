package com.upi.reconcile.connectors.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Response DTO for {@code GET /api/connections} — lists a merchant's
 * gateway connections.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConnectionDto {

    private UUID connectionId;
    private String gateway;
    private String status;
    private OffsetDateTime connectedAt;
    private OffsetDateTime disconnectedAt;
}
