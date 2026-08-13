package com.upi.reconcile.connectors;

import com.upi.reconcile.connectors.crypto.AesGcmEncryptor;
import com.upi.reconcile.connectors.domain.Merchant;
import com.upi.reconcile.connectors.domain.MerchantGatewayConnection;
import com.upi.reconcile.connectors.domain.MerchantGatewayConnectionRepository;
import com.upi.reconcile.domain.Bank;
import com.upi.reconcile.domain.StateTransition;
import com.upi.reconcile.domain.StateTransitionRepository;
import com.upi.reconcile.domain.StateMachine;
import com.upi.reconcile.domain.Transaction;
import com.upi.reconcile.domain.TransactionEvent;
import com.upi.reconcile.domain.TransactionRepository;
import com.upi.reconcile.domain.TransactionState;
import com.upi.reconcile.domain.TransactionStateChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GatewayResolutionServiceTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private StateTransitionRepository stateTransitionRepository;
    @Mock private MerchantGatewayConnectionRepository connectionRepository;
    @Mock private RazorpayApiClient razorpayApiClient;
    @Mock private AesGcmEncryptor encryptor;
    @Mock private ApplicationEventPublisher eventPublisher;

    private GatewayResolutionService service;
    private StateMachine stateMachine;

    private static final String ENCRYPTED_KEY = "enc_key";
    private static final String ENCRYPTED_SECRET = "enc_secret";
    private static final String DECRYPTED_KEY = "rzp_test_abc123";
    private static final String DECRYPTED_SECRET = "secret_xyz789";

    @BeforeEach
    void setUp() {
        stateMachine = new StateMachine();
        service = new GatewayResolutionService(
                transactionRepository,
                stateTransitionRepository,
                connectionRepository,
                razorpayApiClient,
                encryptor,
                stateMachine,
                eventPublisher
        );
    }

    private Transaction buildPendingTransaction(String paymentId, UUID connectionId) {
        Bank remitter = Bank.builder().bankId(UUID.randomUUID()).name("HDFC Bank").build();
        Bank beneficiary = Bank.builder().bankId(UUID.randomUUID()).name("ICICI Bank").build();
        return Transaction.builder()
                .txnId(UUID.randomUUID())
                .idempotencyKey(paymentId)
                .connectionId(connectionId)
                .remitterBank(remitter)
                .beneficiaryBank(beneficiary)
                .amountInr(BigDecimal.valueOf(500))
                .state(TransactionState.PENDING_RECONCILIATION)
                .createdAt(OffsetDateTime.now().minusMinutes(30))
                .build();
    }

    private MerchantGatewayConnection buildRazorpayConnection(UUID connectionId) {
        Merchant merchant = Merchant.builder()
                .merchantId(UUID.randomUUID())
                .name("Test Merchant")
                .build();
        return MerchantGatewayConnection.builder()
                .connectionId(connectionId)
                .merchant(merchant)
                .gateway("razorpay")
                .encryptedApiKey(ENCRYPTED_KEY)
                .encryptedApiSecret(ENCRYPTED_SECRET)
                .webhookSecret("test_webhook_secret")
                .status(MerchantGatewayConnection.STATUS_ACTIVE)
                .connectedAt(OffsetDateTime.now())
                .build();
    }

    @Test
    @DisplayName("Razorpay captured → transitions to SUCCESS with gateway reason")
    void razorpayCaptured_transitionsToSuccess() {
        String paymentId = "pay_TestCaptured123";
        UUID connId = UUID.randomUUID();
        Transaction txn = buildPendingTransaction(paymentId, connId);
        MerchantGatewayConnection connection = buildRazorpayConnection(connId);

        when(transactionRepository.findByState(TransactionState.PENDING_RECONCILIATION))
                .thenReturn(List.of(txn));
        when(connectionRepository.findById(connId)).thenReturn(Optional.of(connection));
        when(encryptor.decrypt(ENCRYPTED_KEY)).thenReturn(DECRYPTED_KEY);
        when(encryptor.decrypt(ENCRYPTED_SECRET)).thenReturn(DECRYPTED_SECRET);
        
        when(razorpayApiClient.fetchPaymentStatus(DECRYPTED_KEY, DECRYPTED_SECRET, paymentId))
                .thenReturn(new RazorpayApiClient.PaymentStatus(paymentId, "captured"));
        
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        service.resolveGatewayTransactions(OffsetDateTime.now());

        assertThat(txn.getState()).isEqualTo(TransactionState.SUCCESS);
        assertThat(txn.getResolvedAt()).isNotNull();
        assertThat(txn.getResolutionReason()).contains("gateway confirmed status=captured");

        ArgumentCaptor<StateTransition> stCaptor = ArgumentCaptor.forClass(StateTransition.class);
        verify(stateTransitionRepository).save(stCaptor.capture());
        StateTransition saved = stCaptor.getValue();
        assertThat(saved.getFromState()).isEqualTo("PENDING_RECONCILIATION");
        assertThat(saved.getToState()).isEqualTo("SUCCESS");
        assertThat(saved.getReason()).contains("webhook had been missed");

        verify(razorpayApiClient, never()).initiateRefund(any(), any(), any());
        verify(eventPublisher).publishEvent(any(TransactionStateChangedEvent.class));
    }

    @Test
    @DisplayName("Prefers externalPaymentRef over idempotencyKey")
    void prefersExternalPaymentRef() {
        String paymentId = "pay_InternalIdempotency123";
        String externalRef = "pay_ExternalActualRef999";
        UUID connId = UUID.randomUUID();
        Transaction txn = buildPendingTransaction(paymentId, connId);
        txn.setExternalPaymentRef(externalRef);
        MerchantGatewayConnection connection = buildRazorpayConnection(connId);

        when(transactionRepository.findByState(TransactionState.PENDING_RECONCILIATION))
                .thenReturn(List.of(txn));
        when(connectionRepository.findById(connId)).thenReturn(Optional.of(connection));
        when(encryptor.decrypt(ENCRYPTED_KEY)).thenReturn(DECRYPTED_KEY);
        when(encryptor.decrypt(ENCRYPTED_SECRET)).thenReturn(DECRYPTED_SECRET);
        
        // Mock using the externalRef!
        when(razorpayApiClient.fetchPaymentStatus(DECRYPTED_KEY, DECRYPTED_SECRET, externalRef))
                .thenReturn(new RazorpayApiClient.PaymentStatus(externalRef, "captured"));
        
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        service.resolveGatewayTransactions(OffsetDateTime.now());

        verify(razorpayApiClient).fetchPaymentStatus(DECRYPTED_KEY, DECRYPTED_SECRET, externalRef);
        assertThat(txn.getState()).isEqualTo(TransactionState.SUCCESS);
    }

    @Test
    @DisplayName("Razorpay failed → refund triggered → transitions to RESOLVED_REFUNDED")
    void razorpayFailed_refundsAndTransitionsToResolvedRefunded() {
        String paymentId = "pay_TestFailed456";
        UUID connId = UUID.randomUUID();
        Transaction txn = buildPendingTransaction(paymentId, connId);
        MerchantGatewayConnection connection = buildRazorpayConnection(connId);

        when(transactionRepository.findByState(TransactionState.PENDING_RECONCILIATION))
                .thenReturn(List.of(txn));
        when(connectionRepository.findById(connId)).thenReturn(Optional.of(connection));
        when(encryptor.decrypt(ENCRYPTED_KEY)).thenReturn(DECRYPTED_KEY);
        when(encryptor.decrypt(ENCRYPTED_SECRET)).thenReturn(DECRYPTED_SECRET);
        
        when(razorpayApiClient.fetchPaymentStatus(DECRYPTED_KEY, DECRYPTED_SECRET, paymentId))
                .thenReturn(new RazorpayApiClient.PaymentStatus(paymentId, "failed"));
        when(razorpayApiClient.initiateRefund(DECRYPTED_KEY, DECRYPTED_SECRET, paymentId))
                .thenReturn(new RazorpayApiClient.RefundResult("rfnd_Test789", "processed"));
        
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        service.resolveGatewayTransactions(OffsetDateTime.now());

        assertThat(txn.getState()).isEqualTo(TransactionState.RESOLVED_REFUNDED);
        verify(razorpayApiClient).initiateRefund(DECRYPTED_KEY, DECRYPTED_SECRET, paymentId);
        
        ArgumentCaptor<StateTransition> stCaptor = ArgumentCaptor.forClass(StateTransition.class);
        verify(stateTransitionRepository).save(stCaptor.capture());
        StateTransition saved = stCaptor.getValue();
        assertThat(saved.getReason()).contains("refund_id=rfnd_Test789");
    }

    @Test
    @DisplayName("No active connection → skips gateway check")
    void noActiveConnection_skipsGatewayCheck() {
        UUID connId = UUID.randomUUID();
        Transaction txn = buildPendingTransaction("pay_123", connId);
        MerchantGatewayConnection connection = buildRazorpayConnection(connId);
        connection.setStatus("DISCONNECTED");

        when(transactionRepository.findByState(TransactionState.PENDING_RECONCILIATION))
                .thenReturn(List.of(txn));
        when(connectionRepository.findById(connId)).thenReturn(Optional.of(connection));

        service.resolveGatewayTransactions(OffsetDateTime.now());

        verify(razorpayApiClient, never()).fetchPaymentStatus(any(), any(), any());
    }

    @Test
    @DisplayName("Simulated connection → skips gateway check")
    void simulatedConnection_skipsGatewayCheck() {
        UUID connId = UUID.randomUUID();
        Transaction txn = buildPendingTransaction("pay_123", connId);
        MerchantGatewayConnection connection = buildRazorpayConnection(connId);
        connection.setGateway("simulated");

        when(transactionRepository.findByState(TransactionState.PENDING_RECONCILIATION))
                .thenReturn(List.of(txn));
        when(connectionRepository.findById(connId)).thenReturn(Optional.of(connection));

        service.resolveGatewayTransactions(OffsetDateTime.now());

        verify(razorpayApiClient, never()).fetchPaymentStatus(any(), any(), any());
    }
}
