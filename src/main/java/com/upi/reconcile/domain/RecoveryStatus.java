package com.upi.reconcile.domain;

/**
 * Recovery status for a {@link ProvisionalRefund}.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@code PENDING_FROM_BANK} — merchant has refunded the customer;
 *       waiting for the bank to reverse the original transaction</li>
 *   <li>{@code RECOVERED} — bank reversed the transaction
 *       (state reached {@code AUTO_REVERSED} or {@code RESOLVED_REFUNDED})</li>
 *   <li>{@code WRITTEN_OFF} — manual write-off (future use)</li>
 * </ol>
 */
public enum RecoveryStatus {
    PENDING_FROM_BANK,
    RECOVERED,
    WRITTEN_OFF
}
