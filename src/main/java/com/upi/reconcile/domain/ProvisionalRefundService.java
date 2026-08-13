package com.upi.reconcile.domain;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Manages the lifecycle of provisional refunds — creation when a merchant
 * refunds out-of-pocket, and automatic recovery when the bank reverses
 * the original transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProvisionalRefundService {

    /** Transaction states eligible for provisional refund creation. */
    private static final Set<TransactionState> ELIGIBLE_STATES = Set.of(
            TransactionState.DEEMED_APPROVED,
            TransactionState.PENDING_RECONCILIATION,
            TransactionState.PENALTY_ACCRUING
    );

    private final TransactionRepository transactionRepository;
    private final ProvisionalRefundRepository provisionalRefundRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Creates a provisional refund record for the given transaction.
     *
     * @param txnId  the transaction ID that the merchant refunded
     * @param amount the amount the merchant refunded to the customer
     * @return the created provisional refund
     * @throws IllegalArgumentException if the transaction is not found,
     *         not in an eligible state, or already has a provisional refund
     */
    @Transactional
    public ProvisionalRefund createProvisionalRefund(UUID txnId, BigDecimal amount) {
        Transaction txn = transactionRepository.findById(txnId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Transaction not found: " + txnId));

        if (!ELIGIBLE_STATES.contains(txn.getState())) {
            throw new IllegalStateException(
                    "Transaction " + txnId + " is in state " + txn.getState()
                            + " — provisional refund only allowed in: " + ELIGIBLE_STATES);
        }

        // Guard against duplicate provisional refunds on the same transaction
        List<ProvisionalRefund> existing = provisionalRefundRepository
                .findByTransaction_TxnId(txnId);
        if (!existing.isEmpty()) {
            throw new IllegalStateException(
                    "Provisional refund already exists for transaction " + txnId);
        }

        ProvisionalRefund refund = ProvisionalRefund.builder()
                .transaction(txn)
                .amountRefundedByMerchant(amount)
                .refundedAt(OffsetDateTime.now())
                .recoveryStatus(RecoveryStatus.PENDING_FROM_BANK)
                .build();
        provisionalRefundRepository.save(refund);

        log.info("Created provisional refund #{} for txn {} — ₹{} (status=PENDING_FROM_BANK)",
                refund.getId(), txnId, amount);

        return refund;
    }

    /**
     * Flips all {@code PENDING_FROM_BANK} provisional refunds for the given
     * transaction to {@code RECOVERED} and emits a
     * {@link ProvisionalRefundRecoveredEvent} for each.
     *
     * <p>Called by {@link ProvisionalRefundRecoveryListener} when a transaction
     * reaches {@code AUTO_REVERSED} or {@code RESOLVED_REFUNDED}.
     *
     * @param txnId the resolved transaction ID
     */
    @Transactional
    public void recoverProvisionalRefunds(UUID txnId) {
        List<ProvisionalRefund> pending = provisionalRefundRepository
                .findByTransaction_TxnIdAndRecoveryStatus(txnId, RecoveryStatus.PENDING_FROM_BANK);

        if (pending.isEmpty()) {
            return; // no provisional refunds linked — nothing to do
        }

        OffsetDateTime now = OffsetDateTime.now();

        for (ProvisionalRefund refund : pending) {
            refund.setRecoveryStatus(RecoveryStatus.RECOVERED);
            provisionalRefundRepository.save(refund);

            eventPublisher.publishEvent(new ProvisionalRefundRecoveredEvent(
                    this,
                    refund.getId(),
                    txnId,
                    refund.getAmountRefundedByMerchant(),
                    now));

            log.info("Provisional refund #{} for txn {} → RECOVERED (₹{})",
                    refund.getId(), txnId, refund.getAmountRefundedByMerchant());
        }
    }
}
