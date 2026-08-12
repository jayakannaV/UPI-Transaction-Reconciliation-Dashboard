package com.upi.reconcile.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProvisionalRefundService} and
 * {@link ProvisionalRefundRecoveryListener}.
 *
 * <p>
 * Verifies:
 * <ul>
 * <li>Happy-path creation for all eligible states</li>
 * <li>Automatic status flip on transaction resolution</li>
 * <li>Event emission on recovery</li>
 * <li>Duplicate guard</li>
 * <li>Ineligible state rejection</li>
 * <li>No-op when no provisional refund exists</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ProvisionalRefundServiceTest {

        @Mock
        private TransactionRepository transactionRepository;
        @Mock
        private ProvisionalRefundRepository provisionalRefundRepository;
        @Mock
        private ApplicationEventPublisher eventPublisher;

        @InjectMocks
        private ProvisionalRefundService service;

        @Captor
        private ArgumentCaptor<ProvisionalRefundRecoveredEvent> eventCaptor;
        @Captor
        private ArgumentCaptor<ProvisionalRefund> refundCaptor;

        private static final UUID TXN_ID = UUID.randomUUID();
        private static final BigDecimal REFUND_AMOUNT = new BigDecimal("500.00");
        private static final OffsetDateTime BASE_TIME = OffsetDateTime.of(2026, 8, 10, 12, 0, 0, 0, ZoneOffset.UTC);

        private Transaction txn;

        @BeforeEach
        void setUp() {
                txn = Transaction.builder()
                                .txnId(TXN_ID)
                                .idempotencyKey("test-key-" + TXN_ID)
                                .amountInr(new BigDecimal("1000.00"))
                                .state(TransactionState.PENALTY_ACCRUING)
                                .createdAt(BASE_TIME)
                                .build();
        }

        // -----------------------------------------------------------------------
        // Creation — eligible states
        // -----------------------------------------------------------------------

        @Nested
        @DisplayName("Provisional refund creation")
        class Creation {

                @ParameterizedTest(name = "Eligible state: {0}")
                @EnumSource(value = TransactionState.class, names = { "DEEMED_APPROVED", "PENDING_RECONCILIATION",
                                "PENALTY_ACCRUING" })
                @DisplayName("Creates refund for eligible states")
                void createsRefundForEligibleStates(TransactionState state) {
                        txn.setState(state);
                        when(transactionRepository.findById(TXN_ID)).thenReturn(Optional.of(txn));
                        when(provisionalRefundRepository.findByTransaction_TxnId(TXN_ID))
                                        .thenReturn(List.of());

                        ProvisionalRefund result = service.createProvisionalRefund(TXN_ID, REFUND_AMOUNT);

                        assertNotNull(result);
                        assertEquals(RecoveryStatus.PENDING_FROM_BANK, result.getRecoveryStatus());
                        assertEquals(REFUND_AMOUNT, result.getAmountRefundedByMerchant());
                        assertEquals(txn, result.getTransaction());

                        verify(provisionalRefundRepository).save(any(ProvisionalRefund.class));
                }

                @Test
                @DisplayName("Throws when transaction not found")
                void throwsOnMissingTransaction() {
                        when(transactionRepository.findById(TXN_ID)).thenReturn(Optional.empty());

                        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                                        () -> service.createProvisionalRefund(TXN_ID, REFUND_AMOUNT));
                        assertTrue(ex.getMessage().contains("not found"));
                }

                @ParameterizedTest(name = "Ineligible state: {0}")
                @EnumSource(value = TransactionState.class, names = { "INITIATED", "SUCCESS", "BUSINESS_DECLINED",
                                "TECHNICAL_DECLINED", "AUTO_REVERSED",
                                "TAT_BREACHED", "RESOLVED_REFUNDED", "ESCALATED" })
                @DisplayName("Throws for ineligible states")
                void throwsOnIneligibleState(TransactionState state) {
                        txn.setState(state);
                        when(transactionRepository.findById(TXN_ID)).thenReturn(Optional.of(txn));

                        IllegalStateException ex = assertThrows(IllegalStateException.class,
                                        () -> service.createProvisionalRefund(TXN_ID, REFUND_AMOUNT));
                        assertTrue(ex.getMessage().contains("only allowed in"));
                }

                @Test
                @DisplayName("Throws on duplicate provisional refund")
                void throwsOnDuplicate() {
                        when(transactionRepository.findById(TXN_ID)).thenReturn(Optional.of(txn));

                        ProvisionalRefund existing = ProvisionalRefund.builder()
                                        .id(1L)
                                        .transaction(txn)
                                        .amountRefundedByMerchant(REFUND_AMOUNT)
                                        .refundedAt(BASE_TIME)
                                        .recoveryStatus(RecoveryStatus.PENDING_FROM_BANK)
                                        .build();
                        when(provisionalRefundRepository.findByTransaction_TxnId(TXN_ID))
                                        .thenReturn(List.of(existing));

                        IllegalStateException ex = assertThrows(IllegalStateException.class,
                                        () -> service.createProvisionalRefund(TXN_ID, REFUND_AMOUNT));
                        assertTrue(ex.getMessage().contains("already exists"));
                }
        }

        // -----------------------------------------------------------------------
        // Recovery — automatic status flip
        // -----------------------------------------------------------------------

        @Nested
        @DisplayName("Automatic recovery on transaction resolution")
        class Recovery {

                @Test
                @DisplayName("Flips PENDING_FROM_BANK → RECOVERED and emits event")
                void flipsStatusOnResolution() {
                        ProvisionalRefund pending = ProvisionalRefund.builder()
                                        .id(42L)
                                        .transaction(txn)
                                        .amountRefundedByMerchant(REFUND_AMOUNT)
                                        .refundedAt(BASE_TIME)
                                        .recoveryStatus(RecoveryStatus.PENDING_FROM_BANK)
                                        .build();

                        when(provisionalRefundRepository.findByTransaction_TxnIdAndRecoveryStatus(
                                        TXN_ID, RecoveryStatus.PENDING_FROM_BANK))
                                        .thenReturn(List.of(pending));

                        service.recoverProvisionalRefunds(TXN_ID);

                        // Verify status was flipped
                        assertEquals(RecoveryStatus.RECOVERED, pending.getRecoveryStatus());
                        verify(provisionalRefundRepository).save(pending);

                        // Verify event was published
                        verify(eventPublisher).publishEvent(eventCaptor.capture());
                        ProvisionalRefundRecoveredEvent event = eventCaptor.getValue();
                        assertEquals(42L, event.getProvisionalRefundId());
                        assertEquals(TXN_ID, event.getTxnId());
                        assertEquals(REFUND_AMOUNT, event.getAmountRecovered());
                        assertNotNull(event.getRecoveredAt());
                }

                @Test
                @DisplayName("No-op when no provisional refund exists for transaction")
                void noOpWhenNoProvisionalRefund() {
                        when(provisionalRefundRepository.findByTransaction_TxnIdAndRecoveryStatus(
                                        TXN_ID, RecoveryStatus.PENDING_FROM_BANK))
                                        .thenReturn(List.of());

                        service.recoverProvisionalRefunds(TXN_ID);

                        verify(provisionalRefundRepository, never()).save(any());
                        verify(eventPublisher, never()).publishEvent(any());
                }
        }

        // -----------------------------------------------------------------------
        // Listener — end-to-end hook into state machine
        // -----------------------------------------------------------------------

        @Nested
        @DisplayName("ProvisionalRefundRecoveryListener integration")
        class ListenerTests {

                private ProvisionalRefundRecoveryListener listener;

                @BeforeEach
                void setUpListener() {
                        listener = new ProvisionalRefundRecoveryListener(service);
                }

                @Test
                @DisplayName("AUTO_REVERSED triggers recovery")
                void autoReversedTriggersRecovery() {
                        ProvisionalRefund pending = ProvisionalRefund.builder()
                                        .id(10L)
                                        .transaction(txn)
                                        .amountRefundedByMerchant(REFUND_AMOUNT)
                                        .refundedAt(BASE_TIME)
                                        .recoveryStatus(RecoveryStatus.PENDING_FROM_BANK)
                                        .build();

                        when(provisionalRefundRepository.findByTransaction_TxnIdAndRecoveryStatus(
                                        TXN_ID, RecoveryStatus.PENDING_FROM_BANK))
                                        .thenReturn(List.of(pending));

                        TransactionStateChangedEvent stateEvent = new TransactionStateChangedEvent(
                                        this, TXN_ID,
                                        TransactionState.PENDING_RECONCILIATION,
                                        TransactionState.AUTO_REVERSED,
                                        BigDecimal.ZERO, null, null, BASE_TIME, null);

                        listener.onTransactionResolved(stateEvent);

                        assertEquals(RecoveryStatus.RECOVERED, pending.getRecoveryStatus());
                        verify(eventPublisher).publishEvent(any(ProvisionalRefundRecoveredEvent.class));
                }

                @Test
                @DisplayName("RESOLVED_REFUNDED triggers recovery")
                void resolvedRefundedTriggersRecovery() {
                        ProvisionalRefund pending = ProvisionalRefund.builder()
                                        .id(20L)
                                        .transaction(txn)
                                        .amountRefundedByMerchant(new BigDecimal("750.00"))
                                        .refundedAt(BASE_TIME)
                                        .recoveryStatus(RecoveryStatus.PENDING_FROM_BANK)
                                        .build();

                        when(provisionalRefundRepository.findByTransaction_TxnIdAndRecoveryStatus(
                                        TXN_ID, RecoveryStatus.PENDING_FROM_BANK))
                                        .thenReturn(List.of(pending));

                        TransactionStateChangedEvent stateEvent = new TransactionStateChangedEvent(
                                        this, TXN_ID,
                                        TransactionState.PENALTY_ACCRUING,
                                        TransactionState.RESOLVED_REFUNDED,
                                        new BigDecimal("300.00"), null, null, BASE_TIME, null);

                        listener.onTransactionResolved(stateEvent);

                        assertEquals(RecoveryStatus.RECOVERED, pending.getRecoveryStatus());
                        verify(eventPublisher).publishEvent(eventCaptor.capture());
                        assertEquals(new BigDecimal("750.00"), eventCaptor.getValue().getAmountRecovered());
                }

                @Test
                @DisplayName("Non-resolution states are ignored by listener")
                void nonResolutionStatesIgnored() {
                        TransactionStateChangedEvent stateEvent = new TransactionStateChangedEvent(
                                        this, TXN_ID,
                                        TransactionState.PENDING_RECONCILIATION,
                                        TransactionState.TAT_BREACHED,
                                        BigDecimal.ZERO, null, null, BASE_TIME, null);

                        listener.onTransactionResolved(stateEvent);

                        // Should never even call the repository
                        verify(provisionalRefundRepository, never())
                                        .findByTransaction_TxnIdAndRecoveryStatus(any(), any());
                }
        }
}
