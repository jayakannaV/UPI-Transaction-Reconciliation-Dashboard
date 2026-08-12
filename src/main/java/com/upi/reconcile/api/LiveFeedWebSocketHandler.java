package com.upi.reconcile.api;

import com.upi.reconcile.connectors.domain.MerchantGatewayConnection;
import com.upi.reconcile.connectors.domain.MerchantGatewayConnectionRepository;
import com.upi.reconcile.domain.ProvisionalRefundRecoveredEvent;
import com.upi.reconcile.domain.SystemicAnomalyEvent;
import com.upi.reconcile.domain.TransactionStateChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Listens for {@link TransactionStateChangedEvent} (published by the batch
 * scheduler and processing service) and broadcasts the payload to
 * STOMP subscribers on {@code /topic/live-feed/{merchantId}}.
 * <p>
 * Also listens for {@link SystemicAnomalyEvent} (published by the anomaly
 * monitor) and broadcasts an {@code ANOMALY_FLAGGED} message on the same
 * topic, enabling the frontend to render a distinct banner.
 * <p>
 * Per-merchant topic routing ensures a merchant only receives events for
 * their own transactions (tenant isolation at the WebSocket layer).
 * <p>
 * Payload shape per ARCHITECTURE.md §7:
 * 
 * <pre>
 * { txn_id, old_state, new_state, penalty_amount_inr, bank_id, timestamp }
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiveFeedWebSocketHandler {

        private final SimpMessagingTemplate messagingTemplate;
        private final MerchantGatewayConnectionRepository connectionRepository;

        @EventListener
        public void onStateChange(TransactionStateChangedEvent event) {
                // Resolve gateway + connection status from the event's connectionId
                String gateway = "simulated";
                String connectionStatus = null;
                if (event.getConnectionId() != null) {
                        Optional<MerchantGatewayConnection> connOpt =
                                        connectionRepository.findById(event.getConnectionId());
                        if (connOpt.isPresent()) {
                                gateway = connOpt.get().getGateway();
                                connectionStatus = connOpt.get().getStatus();
                        }
                }

                LiveFeedMessage message = new LiveFeedMessage(
                                event.getTxnId(),
                                event.getFromState() != null ? event.getFromState().name() : null,
                                event.getToState().name(),
                                event.getPenaltyAmountInr(),
                                event.getRemitterBankId(),
                                event.getTransitionedAt(),
                                gateway,
                                connectionStatus);

                // Route to per-merchant topic for tenant isolation
                if (event.getMerchantId() != null) {
                        String destination = "/topic/live-feed/" + event.getMerchantId();
                        messagingTemplate.convertAndSend(destination, message);
                        log.debug("Broadcast state change to {}: {} → {} (txn={})",
                                        destination, message.oldState(), message.newState(), message.txnId());
                } else {
                        // Fallback: global topic for transactions without merchant context
                        messagingTemplate.convertAndSend("/topic/live-feed", message);
                        log.debug("Broadcast state change to /topic/live-feed (no merchant): {} → {} (txn={})",
                                        message.oldState(), message.newState(), message.txnId());
                }
        }

        /**
         * Broadcasts systemic anomaly events on the same WebSocket topic
         * with a distinct {@code eventType} so the frontend can show a
         * banner separate from individual transaction updates.
         */
        @EventListener
        public void onSystemicAnomaly(SystemicAnomalyEvent event) {
                AnomalyMessage message = new AnomalyMessage(
                                "ANOMALY_FLAGGED",
                                event.getBankId(),
                                event.getBankName(),
                                event.getFailureRateNow(),
                                event.getHistoricalBaseline(),
                                event.getAffectedTransactionCount(),
                                event.getDetectedAt());

                // Anomalies are broadcast globally — they affect all merchants using the bank
                messagingTemplate.convertAndSend("/topic/live-feed", message);

                log.warn("Broadcast ANOMALY_FLAGGED to /topic/live-feed: {} — {} txns affected",
                                event.getBankName(), event.getAffectedTransactionCount());
        }

        /**
         * Immutable record for the live-feed JSON payload.
         */
        public record LiveFeedMessage(
                        UUID txnId,
                        String oldState,
                        String newState,
                        BigDecimal penaltyAmountInr,
                        UUID bankId,
                        OffsetDateTime timestamp,
                        String gateway,
                        String connectionStatus) {
        }

        /**
         * Immutable record for the anomaly-flagged JSON payload (§5).
         */
        public record AnomalyMessage(
                        String eventType,
                        UUID bankId,
                        String bankName,
                        double failureRateNow,
                        double historicalBaseline,
                        long affectedCount,
                        OffsetDateTime timestamp) {
        }

        // ── Provisional Refund Recovery ───────────────────────────────

        /**
         * Broadcasts provisional refund recovery events on the same WebSocket
         * topic with a distinct {@code eventType} so the frontend can notify
         * merchants that their out-of-pocket refund has been recovered.
         */
        @EventListener
        public void onProvisionalRefundRecovered(ProvisionalRefundRecoveredEvent event) {
                ProvisionalRefundRecoveredMessage message = new ProvisionalRefundRecoveredMessage(
                                "PROVISIONAL_REFUND_RECOVERED",
                                event.getProvisionalRefundId(),
                                event.getTxnId(),
                                event.getAmountRecovered(),
                                event.getRecoveredAt());

                messagingTemplate.convertAndSend("/topic/live-feed", message);

                log.info("Broadcast PROVISIONAL_REFUND_RECOVERED to /topic/live-feed: refund #{} txn {} ₹{}",
                                event.getProvisionalRefundId(), event.getTxnId(), event.getAmountRecovered());
        }

        /**
         * Immutable record for the provisional-refund-recovered JSON payload.
         */
        public record ProvisionalRefundRecoveredMessage(
                        String eventType,
                        Long provisionalRefundId,
                        UUID txnId,
                        BigDecimal amountRecovered,
                        OffsetDateTime timestamp) {
        }
}
