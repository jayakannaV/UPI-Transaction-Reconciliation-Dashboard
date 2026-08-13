package com.upi.reconcile.connectors;

import com.upi.reconcile.connectors.crypto.AesGcmEncryptor;
import com.upi.reconcile.connectors.domain.MerchantGatewayConnection;
import com.upi.reconcile.connectors.domain.MerchantGatewayConnectionRepository;
import com.upi.reconcile.domain.StateTransition;
import com.upi.reconcile.domain.StateTransitionRepository;
import com.upi.reconcile.domain.StateMachine;
import com.upi.reconcile.domain.Transaction;
import com.upi.reconcile.domain.TransactionEvent;
import com.upi.reconcile.domain.TransactionRepository;
import com.upi.reconcile.domain.TransactionState;
import com.upi.reconcile.domain.TransactionStateChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Gateway-driven resolution service for Razorpay-sourced transactions.
 *
 * <p>
 * When a transaction sourced from Razorpay enters {@code PENALTY_ACCRUING},
 * this service calls Razorpay's real API to determine the true payment status
 * and takes corrective action:
 *
 * <ul>
 * <li>If Razorpay says {@code captured} → transition to {@code SUCCESS}
 * (auto-corrected via gateway status check)</li>
 * <li>If Razorpay says {@code failed}/{@code created}/{@code authorized} →
 * initiate refund via API, then transition to {@code RESOLVED_REFUNDED}</li>
 * <li>If Razorpay says {@code refunded} → transition to
 * {@code RESOLVED_REFUNDED}
 * (already refunded)</li>
 * </ul>
 *
 * <p>
 * This is invoked from
 * {@link com.upi.reconcile.scheduler.BatchResolutionScheduler}
 * as a new sweep that runs <em>before</em> the simulated NPCI batch resolution,
 * ensuring Razorpay transactions get real API-driven resolution.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GatewayResolutionService {

    private final TransactionRepository transactionRepository;
    private final StateTransitionRepository stateTransitionRepository;
    private final MerchantGatewayConnectionRepository connectionRepository;
    private final RazorpayApiClient razorpayApiClient;
    private final AesGcmEncryptor encryptor;
    private final StateMachine stateMachine;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Resolves genuine real-gateway transactions in PENDING_RECONCILIATION
     * by checking their real payment status.
     *
     * @param now the current timestamp for audit records
     */
    @Transactional
    public void resolveGatewayTransactions(OffsetDateTime now) {
        List<Transaction> pending = transactionRepository.findByStateIn(
                List.of(TransactionState.PENDING_RECONCILIATION, TransactionState.PENALTY_ACCRUING));

        if (pending.isEmpty()) {
            return;
        }

        int count = 0;

        for (Transaction txn : pending) {
            if (txn.getConnectionId() == null) {
                continue;
            }

            MerchantGatewayConnection connection = connectionRepository.findById(txn.getConnectionId()).orElse(null);
            
            if (connection == null 
                    || !MerchantGatewayConnection.STATUS_ACTIVE.equals(connection.getStatus())
                    || "simulated".equals(connection.getGateway())) {
                continue;
            }

            if ("razorpay".equals(connection.getGateway())) {
                String apiKey;
                String apiSecret;
                try {
                    apiKey = encryptor.decrypt(connection.getEncryptedApiKey());
                    apiSecret = encryptor.decrypt(connection.getEncryptedApiSecret());
                } catch (Exception e) {
                    log.error("Failed to decrypt Razorpay credentials for connection {} — skipping",
                            connection.getConnectionId(), e);
                    continue;
                }

                try {
                    resolveOneTransaction(txn, apiKey, apiSecret, now);
                    count++;
                } catch (Exception e) {
                    log.error("Gateway resolution failed for txn {} (payment_id={}) — will retry next tick",
                            txn.getTxnId(), txn.getIdempotencyKey(), e);
                }
            }
        }

        if (count > 0) {
            log.info("🔌 Gateway resolution sweep — resolved {} gateway transactions", count);
        }
    }

    /**
     * Resolves a single Razorpay transaction by checking its real payment status.
     */
    private void resolveOneTransaction(Transaction txn, String apiKey, String apiSecret,
            OffsetDateTime now) {
        String paymentId = txn.getExternalPaymentRef() != null && !txn.getExternalPaymentRef().isBlank()
                ? txn.getExternalPaymentRef()
                : txn.getIdempotencyKey(); // fallback if external ref is missing

        RazorpayApiClient.PaymentStatus status = razorpayApiClient.fetchPaymentStatus(apiKey, apiSecret, paymentId);

        switch (status.status()) {
            case "captured" -> {
                String reason = String.format("Checked Razorpay payment status via GET /payments/%s — gateway confirmed status=captured, webhook had been missed. Marked SUCCESS.", paymentId);
                applyTransition(txn, TransactionEvent.GATEWAY_STATUS_CHECK_SUCCESS, reason, now);
                txn.setResolvedAt(now);
                transactionRepository.save(txn);

                log.info("✅ Txn {} auto-corrected to SUCCESS via Razorpay API (payment_id={})",
                        txn.getTxnId(), paymentId);
            }

            case "refunded" -> {
                String reason = String.format("Checked Razorpay payment status via GET /payments/%s — gateway confirmed status=refunded. Marked RESOLVED_REFUNDED.", paymentId);
                applyTransition(txn, TransactionEvent.GATEWAY_REFUND_COMPLETED, reason, now);
                txn.setResolvedAt(now);
                transactionRepository.save(txn);

                log.info("✅ Txn {} confirmed REFUNDED via Razorpay API (payment_id={})",
                        txn.getTxnId(), paymentId);
            }

            case "failed", "created", "authorized" -> {
                try {
                    RazorpayApiClient.RefundResult refund = razorpayApiClient.initiateRefund(apiKey, apiSecret, paymentId);

                    String reason = String.format("Checked Razorpay payment status — gateway confirmed status=%s. Issued refund via POST /payments/%s/refund, refund_id=%s. Marked RESOLVED_REFUNDED.", 
                            status.status(), paymentId, refund.refundId());
                            
                    applyTransition(txn, TransactionEvent.GATEWAY_REFUND_COMPLETED, reason, now);
                    txn.setResolvedAt(now);
                    transactionRepository.save(txn);

                    log.info("💸 Txn {} auto-refunded via Razorpay API — refund_id={} (payment_id={})",
                            txn.getTxnId(), refund.refundId(), paymentId);
                } catch (Exception e) {
                    log.error("Razorpay refund failed for txn {} (payment_id={}) — {}",
                            txn.getTxnId(), paymentId, e.getMessage());
                }
            }

            default -> log.warn("Unexpected Razorpay payment status '{}' for payment_id={} — skipping",
                    status.status(), paymentId);
        }
    }

    /**
     * Applies a state transition, records audit trail, and publishes event.
     */
    private void applyTransition(Transaction txn, TransactionEvent event,
            String reason, OffsetDateTime at) {
        TransactionState fromState = txn.getState();
        TransactionState toState = stateMachine.transition(fromState, event);

        if (toState == TransactionState.SUCCESS || toState == TransactionState.RESOLVED_REFUNDED) {
            txn.setResolutionReason(reason);
        }

        txn.setState(toState);
        transactionRepository.save(txn);

        StateTransition st = StateTransition.builder()
                .transaction(txn)
                .fromState(fromState.name())
                .toState(toState.name())
                .transitionedAt(at)
                .reason(reason)
                .build();
        stateTransitionRepository.save(st);

        eventPublisher.publishEvent(new TransactionStateChangedEvent(
                this,
                txn.getTxnId(),
                fromState,
                toState,
                txn.getPenaltyAmountInr(),
                txn.getRemitterBank() != null ? txn.getRemitterBank().getBankId() : null,
                txn.getBeneficiaryBank() != null ? txn.getBeneficiaryBank().getBankId() : null,
                at,
                txn.getMerchantOwner() != null ? txn.getMerchantOwner().getMerchantId() : null,
                txn.getConnectionId(),
                txn.getResolutionReason()));

        log.debug("Transition: {} → {} [{}] for txn {}", fromState, toState, reason, txn.getTxnId());
    }
}
