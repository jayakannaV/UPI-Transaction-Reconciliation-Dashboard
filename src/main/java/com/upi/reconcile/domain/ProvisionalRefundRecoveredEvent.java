package com.upi.reconcile.domain;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Published when a provisional refund's recovery status is flipped to
 * {@link RecoveryStatus#RECOVERED} after the linked transaction reaches
 * a terminal resolution state ({@code AUTO_REVERSED} or {@code RESOLVED_REFUNDED}).
 *
 * <p>Downstream listeners (e.g. WebSocket layer) use this to notify
 * the merchant frontend in real time.
 */
@Getter
public class ProvisionalRefundRecoveredEvent extends ApplicationEvent {

    private final Long provisionalRefundId;
    private final UUID txnId;
    private final BigDecimal amountRecovered;
    private final OffsetDateTime recoveredAt;

    public ProvisionalRefundRecoveredEvent(Object source,
                                           Long provisionalRefundId,
                                           UUID txnId,
                                           BigDecimal amountRecovered,
                                           OffsetDateTime recoveredAt) {
        super(source);
        this.provisionalRefundId = provisionalRefundId;
        this.txnId = txnId;
        this.amountRecovered = amountRecovered;
        this.recoveredAt = recoveredAt;
    }
}
