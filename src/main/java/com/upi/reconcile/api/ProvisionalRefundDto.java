package com.upi.reconcile.api;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Response DTO for provisional refund records.
 */
@Builder
public record ProvisionalRefundDto(
        Long id,
        UUID txnId,
        BigDecimal amountRefundedByMerchant,
        OffsetDateTime refundedAt,
        String recoveryStatus
) {}
