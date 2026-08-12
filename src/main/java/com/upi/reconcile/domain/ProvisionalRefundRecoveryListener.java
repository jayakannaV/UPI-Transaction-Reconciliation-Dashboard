package com.upi.reconcile.domain;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Listens for {@link TransactionStateChangedEvent} and automatically
 * recovers any linked provisional refunds when the transaction reaches
 * a resolution state ({@code AUTO_REVERSED} or {@code RESOLVED_REFUNDED}).
 *
 * <p>Hooks into the existing event pipeline — no modifications to the
 * {@link com.upi.reconcile.scheduler.BatchResolutionScheduler} or
 * {@link com.upi.reconcile.connectors.GatewayResolutionService} are needed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProvisionalRefundRecoveryListener {

    private final ProvisionalRefundService provisionalRefundService;

    @EventListener
    public void onTransactionResolved(TransactionStateChangedEvent event) {
        TransactionState toState = event.getToState();

        if (toState == TransactionState.AUTO_REVERSED
                || toState == TransactionState.RESOLVED_REFUNDED) {

            log.debug("Transaction {} resolved to {} — checking for provisional refunds",
                    event.getTxnId(), toState);

            provisionalRefundService.recoverProvisionalRefunds(event.getTxnId());
        }
    }
}
