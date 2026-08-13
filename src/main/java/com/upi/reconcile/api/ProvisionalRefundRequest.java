package com.upi.reconcile.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Request body for {@code POST /api/transactions/{txnId}/provisional-refund}.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProvisionalRefundRequest {

    @NotNull(message = "amount_refunded_by_merchant is required")
    @Positive(message = "amount_refunded_by_merchant must be positive")
    private BigDecimal amountRefundedByMerchant;
}
