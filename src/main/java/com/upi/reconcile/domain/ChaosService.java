package com.upi.reconcile.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.upi.reconcile.api.WebhookRequest;
import com.upi.reconcile.api.WebhookResponse;
import com.upi.reconcile.config.TimeCompressionConfig;
import com.upi.reconcile.connectors.PaymentGatewayConnector;
import com.upi.reconcile.connectors.domain.MerchantGatewayConnection;
import com.upi.reconcile.connectors.domain.MerchantGatewayConnectionRepository;
import com.upi.reconcile.ingestion.TransactionEventProducer;
import com.upi.reconcile.ingestion.WebhookResultHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Business logic for demo-only chaos control endpoints.
 *
 * <p>This bean is <strong>only registered when {@code SPRING_PROFILES_ACTIVE=demo}</strong>
 * is set. Every method enforces merchant ownership — a merchant can only affect
 * their own transactions, never another merchant's data.
 *
 * <p>Wherever possible, operations route through the real service layer
 * ({@link TransactionProcessingService}, {@link com.upi.reconcile.connectors.RazorpayConnector})
 * to prove the actual code paths work, not just a shortcut.
 */
@Slf4j
@Service
@Profile("demo")
@RequiredArgsConstructor
public class ChaosService {

    private final TransactionRepository transactionRepository;
    private final TransactionProcessingService transactionProcessingService;
    private final StateTransitionRepository stateTransitionRepository;
    private final MerchantGatewayConnectionRepository connectionRepository;
    private final TransactionEventProducer producer;
    private final WebhookResultHolder resultHolder;
    private final ObjectMapper objectMapper;
    private final TimeCompressionConfig timeConfig;
    private final BankRepository bankRepository;
    private final List<PaymentGatewayConnector> connectors;

    // ── 1. Fire Duplicate ────────────────────────────────────────────────

    /**
     * Re-sends the same idempotency_key as an existing transaction through the
     * Kafka pipeline. The dedup path (Redis fast-path → DB fallback) should
     * detect it and return {@code DUPLICATE_IGNORED} without creating a new
     * transaction.
     *
     * @param txnId      the transaction whose key to re-send
     * @param merchantId the authenticated merchant's ID (ownership check)
     * @return the {@link WebhookResponse} — expected to be DUPLICATE_IGNORED
     * @throws IllegalArgumentException if the transaction doesn't belong to this merchant
     */
    public WebhookResponse fireDuplicate(UUID txnId, UUID merchantId) throws Exception {
        Transaction txn = transactionRepository.findByTxnIdAndMerchantOwner_MerchantId(txnId, merchantId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Transaction not found or not owned by this merchant"));

        String idempotencyKey = txn.getIdempotencyKey();

        // Build the same webhook request shape that would come from the connector
        WebhookRequest duplicate = WebhookRequest.builder()
                .idempotencyKey(idempotencyKey)
                .remitterBankId(txn.getRemitterBank().getBankId())
                .beneficiaryBankId(txn.getBeneficiaryBank().getBankId())
                .amountInr(txn.getAmountInr())
                .orderReference(txn.getOrderReference() != null ? txn.getOrderReference() : "chaos-dup-" + txnId)
                .declineCode(txn.getDeclineCode())
                .sourceGateway(txn.getSourceGateway())
                .merchantId(merchantId)
                .connectionId(txn.getConnectionId())
                .build();

        // Register a future BEFORE publishing to Kafka
        CompletableFuture<WebhookResponse> future = resultHolder.register(idempotencyKey);

        String json = objectMapper.writeValueAsString(duplicate);
        producer.publishWebhookEvent(idempotencyKey, json);

        // Block until the consumer processes the message (should be fast — dedup)
        WebhookResponse response = resultHolder.await(future);

        log.info("🔁 Chaos fire-duplicate — txn={}, key={}, result={}",
                txnId, idempotencyKey, response.getState());

        return response;
    }

    // ── 2. Create Deemed Approved ────────────────────────────────────────

    /**
     * Creates a new transaction that deliberately skips online confirmation
     * and enters DEEMED_APPROVED via the state machine's natural
     * {@code NO_CONFIRMATION} path.
     *
     * @param merchantId the authenticated merchant's ID
     * @return the created transaction in DEEMED_APPROVED state
     */
    @Transactional
    public Transaction createDeemedApproved(UUID merchantId) {
        // Always use simulated gateway — real gateway connections would
        // auto-resolve via gateway status check, defeating the demo
        String gateway = "simulated";
        UUID connectionId = null;

        // Pick default bank IDs (the first two available banks)
        List<Bank> banks = bankRepository.findAll();
        if (banks.size() < 2) {
            throw new IllegalStateException("Need at least 2 banks seeded to create transactions");
        }
        UUID remitterBankId = banks.get(0).getBankId();
        UUID beneficiaryBankId = banks.get(1).getBankId();

        String idempotencyKey = "chaos-deemed-" + UUID.randomUUID();

        Transaction txn = transactionProcessingService.processNewTransaction(
                idempotencyKey,
                remitterBankId,
                beneficiaryBankId,
                randomAmount(),
                "CHAOS-DEEMED-" + System.currentTimeMillis(),
                "NO_CONFIRMATION",   // triggers INITIATED → DEEMED_APPROVED
                gateway,
                merchantId,
                connectionId);

        log.info("🎭 Chaos create-deemed-approved — txn={}, state={}, gateway={}",
                txn.getTxnId(), txn.getState(), gateway);

        return txn;
    }

    // ── 3. Force Breach ──────────────────────────────────────────────────

    /**
     * Sets the TAT deadline on a PENDING_RECONCILIATION transaction to a few
     * seconds in the future so the next scheduler tick breaches it, rather
     * than waiting for the normal simulated-day timer.
     *
     * @param txnId      the transaction to force-breach
     * @param merchantId the authenticated merchant's ID (ownership check)
     * @return the updated transaction
     * @throws IllegalArgumentException if the transaction isn't owned or isn't PENDING_RECONCILIATION
     */
    @Transactional
    public Transaction forceBreach(UUID txnId, UUID merchantId) {
        Transaction txn = transactionRepository.findByTxnIdAndMerchantOwner_MerchantId(txnId, merchantId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Transaction not found or not owned by this merchant"));

        if (txn.getState() != TransactionState.PENDING_RECONCILIATION) {
            throw new IllegalStateException(
                    "Transaction must be in PENDING_RECONCILIATION state, but is " + txn.getState());
        }

        // Remove restriction: we now allow force-breach on real gateway transactions 
        // to speed up demo reconciliation cycles for real API checks as well.

        // Set the deadline to 3 seconds from now — the next scheduler tick
        // (every 7 seconds) will breach it
        OffsetDateTime nearFuture = OffsetDateTime.now().plusSeconds(3);
        txn.setTatDeadline(nearFuture);
        txn.setPenaltyStartAt(nearFuture.plusSeconds(timeConfig.getSimulatedDaySeconds()));
        transactionRepository.save(txn);

        log.info("⏰ Chaos force-breach — txn={}, new deadline={} (breach in ~3s)",
                txn.getTxnId(), nearFuture);

        return txn;
    }

    // ── 4. Trigger Anomaly ───────────────────────────────────────────────

    /**
     * Fires a burst of TECHNICAL_DECLINED events against the given bank,
     * enough to cross the anomaly detector's 3× threshold.
     *
     * <p>Computes the needed count: if the bank's historical baseline is
     * {@code td_rate + deemed_approved_rate}, and the threshold is 3× that,
     * we need enough failures that the failure rate exceeds the threshold.
     *
     * @param bankId     the bank to trigger anomaly for
     * @param merchantId the authenticated merchant's ID
     * @return the list of TECHNICAL_DECLINED transactions created
     */
    @Transactional
    public List<Transaction> triggerAnomaly(UUID bankId, UUID merchantId) {
        Bank bank = bankRepository.findById(bankId)
                .orElseThrow(() -> new IllegalArgumentException("Bank not found: " + bankId));

        // Compute how many TD events we need to cross 3× threshold
        double historicalBaseline = 0.0;
        if (bank.getHistoricalTdRate() != null) {
            historicalBaseline += bank.getHistoricalTdRate().doubleValue();
        }
        if (bank.getHistoricalDeemedApprovedRate() != null) {
            historicalBaseline += bank.getHistoricalDeemedApprovedRate().doubleValue();
        }

        // We need failure_rate > 3 × baseline
        // With N total txns and F failures: F/N > 3×baseline
        // Strategy: create a batch where all are failures
        // We need at least enough to show up in the anomaly window
        // Minimum burst: max(5, enough to be > 3× threshold if there are other txns)
        int burstSize = Math.max(8, (int) Math.ceil(3.0 * historicalBaseline * 100) + 2);
        if (burstSize > 30) {
            burstSize = 30; // cap for safety
        }

        // Find first ACTIVE connection for this merchant
        List<MerchantGatewayConnection> connections = connectionRepository
                .findByMerchant_MerchantId(merchantId);
        Optional<MerchantGatewayConnection> activeConn = connections.stream()
                .filter(c -> MerchantGatewayConnection.STATUS_ACTIVE.equals(c.getStatus()))
                .findFirst();

        String gateway = activeConn.map(MerchantGatewayConnection::getGateway).orElse("simulated");
        UUID connectionId = activeConn.map(MerchantGatewayConnection::getConnectionId).orElse(null);

        // Pick another bank as beneficiary
        List<Bank> allBanks = bankRepository.findAll();
        UUID beneficiaryBankId = allBanks.stream()
                .filter(b -> !b.getBankId().equals(bankId))
                .findFirst()
                .map(Bank::getBankId)
                .orElse(bankId); // fallback to same bank

        List<Transaction> created = new ArrayList<>();
        for (int i = 0; i < burstSize; i++) {
            Transaction txn = transactionProcessingService.processNewTransaction(
                    "chaos-anomaly-" + UUID.randomUUID(),
                    bankId,           // remitter = target bank
                    beneficiaryBankId,
                    randomAmount(),
                    "CHAOS-ANOMALY-" + i + "-" + System.currentTimeMillis(),
                    "MALFORMED_BANK_ID",  // TD code → TECHNICAL_DECLINED
                    gateway,
                    merchantId,
                    connectionId);
            created.add(txn);
        }

        log.info("🚨 Chaos trigger-anomaly — bank={}, burst={} TD events for merchant={}",
                bank.getName(), burstSize, merchantId);

        return created;
    }

    // ── 5. Simulate Razorpay Payment ─────────────────────────────────────

    /**
     * Builds a realistic Razorpay-shaped test payload and runs it through
     * the REAL RazorpayConnector path — proving the live integration works,
     * not just the simulator.
     *
     * <p>If the merchant has no ACTIVE Razorpay connection, returns null
     * (the controller translates this into a 400 error).
     *
     * @param merchantId the authenticated merchant's ID
     * @return the webhook response from the Razorpay pipeline, or null if no connection
     */
    public WebhookResponse simulateRazorpayPayment(UUID merchantId) throws Exception {
        // Check for ACTIVE Razorpay connection
        Optional<MerchantGatewayConnection> connOpt = connectionRepository
                .findByMerchant_MerchantIdAndGatewayAndStatus(
                        merchantId, "razorpay", MerchantGatewayConnection.STATUS_ACTIVE);

        if (connOpt.isEmpty()) {
            return null; // controller returns 400
        }

        MerchantGatewayConnection connection = connOpt.get();

        // Find the RazorpayConnector
        PaymentGatewayConnector razorpayConnector = connectors.stream()
                .filter(c -> "razorpay".equals(c.gatewayName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("RazorpayConnector not found"));

        // Build a realistic Razorpay webhook payload
        String fakePaymentId = "pay_chaos_" + UUID.randomUUID().toString().substring(0, 12);
        String fakeOrderId = "order_chaos_" + System.currentTimeMillis();
        long amountPaise = 50000 + (long) (Math.random() * 450000); // ₹500 - ₹5000

        Map<String, Object> entity = new HashMap<>();
        entity.put("id", fakePaymentId);
        entity.put("amount", amountPaise);
        entity.put("currency", "INR");
        entity.put("status", "captured");
        entity.put("order_id", fakeOrderId);
        entity.put("error_code", null);
        entity.put("error_description", null);

        Map<String, Object> payment = Map.of("entity", entity);
        Map<String, Object> payload = Map.of("payment", payment);
        Map<String, Object> rawPayload = new HashMap<>();
        rawPayload.put("event", "payment.captured");
        rawPayload.put("payload", payload);

        // Normalize through the REAL RazorpayConnector
        WebhookRequest normalized = razorpayConnector.normalizeWebhookPayload(rawPayload);
        normalized.setSourceGateway("razorpay");
        normalized.setMerchantId(merchantId);
        normalized.setConnectionId(connection.getConnectionId());

        // Register future BEFORE publishing to Kafka
        CompletableFuture<WebhookResponse> future = resultHolder.register(normalized.getIdempotencyKey());

        String json = objectMapper.writeValueAsString(normalized);
        producer.publishWebhookEvent(normalized.getIdempotencyKey(), json);

        WebhookResponse response = resultHolder.await(future);

        log.info("🔌 Chaos simulate-razorpay — payment_id={}, txn={}, state={}",
                fakePaymentId, response.getTxnId(), response.getState());

        return response;
    }

    // ── 6. Simulate Missed Webhook ───────────────────────────────────────

    /**
     * Stages a transaction as "webhook missed" using a REAL previously-captured
     * Razorpay payment reference, rather than a fabricated one.
     */
    @Transactional
    public Transaction simulateMissedWebhook(UUID merchantId) {
        // Requires the current merchant to have at least one existing transaction with gateway="razorpay" and state=SUCCESS
        Optional<Transaction> txnOpt = transactionRepository.findFirstByMerchantOwner_MerchantIdAndSourceGatewayAndStateOrderByCreatedAtDesc(merchantId, "razorpay", TransactionState.SUCCESS);
        if (txnOpt.isEmpty()) {
            throw new IllegalStateException("Send one real test payment through Razorpay checkout first, then this action will be available.");
        }
        
        Transaction source = txnOpt.get();
        
        // Pick that merchant's most recent real SUCCESS Razorpay transaction and read its external_payment_ref (or idempotency_key if null).
        String externalRef = source.getExternalPaymentRef() != null ? source.getExternalPaymentRef() : source.getIdempotencyKey();
        
        // Create new transaction row
        Transaction txn = new Transaction();
        txn.setTxnId(UUID.randomUUID());
        txn.setIdempotencyKey("chaos-demo-" + UUID.randomUUID().toString().substring(0, 8));
        txn.setExternalPaymentRef(externalRef);
        txn.setConnectionId(source.getConnectionId());
        txn.setState(TransactionState.PENDING_RECONCILIATION);
        
        // Set other required fields from source (banks, amount, etc.)
        txn.setMerchantOwner(source.getMerchantOwner());
        txn.setRemitterBank(source.getRemitterBank());
        txn.setBeneficiaryBank(source.getBeneficiaryBank());
        txn.setAmountInr(source.getAmountInr());
        txn.setSourceGateway(source.getSourceGateway());
        txn.setCreatedAt(OffsetDateTime.now());
        
        log.info("🎯 Chaos simulate-missed-webhook — staged txn={} with externalRef={}", txn.getTxnId(), externalRef);
        
        // Save directly
        Transaction saved = transactionRepository.save(txn);
        
        // Log the initial state transition so it shows up in history
        recordTransition(saved, null, TransactionState.PENDING_RECONCILIATION, 
                "Chaos simulate-missed-webhook (staged for recovery)", OffsetDateTime.now());
                
        return saved;
    }

    // ── 7. Seed Demo Data ────────────────────────────────────────────────

    /**
     * Creates a small mixed batch of transactions for the current merchant:
     * <ul>
     *   <li>3 × SUCCESS — normal completed payments</li>
     *   <li>1 × DEEMED_APPROVED — awaiting confirmation</li>
     *   <li>1 × PENDING_RECONCILIATION with near-future deadline (heading toward breach)</li>
     *   <li>1 × PENALTY_ACCRUING — already past TAT deadline</li>
     * </ul>
     *
     * @param merchantId the authenticated merchant's ID
     * @return all created transactions
     */
    @Transactional
    public List<Transaction> seedDemoData(UUID merchantId) {
        List<Bank> banks = bankRepository.findAll();
        if (banks.size() < 2) {
            throw new IllegalStateException("Need at least 2 banks seeded to create transactions");
        }

        UUID remitterBankId = banks.get(0).getBankId();
        UUID beneficiaryBankId = banks.get(1).getBankId();

        // Always use simulated gateway for seed data — real gateway connections
        // would trigger GatewayResolutionService API calls on the fabricated
        // idempotency keys, and the complaint generator would reject them
        String gateway = "simulated";
        UUID connectionId = null;

        List<Transaction> result = new ArrayList<>();
        OffsetDateTime now = OffsetDateTime.now();

        // ── 3 × SUCCESS ──────────────────────────────────────────────────
        for (int i = 0; i < 3; i++) {
            Transaction txn = transactionProcessingService.processNewTransaction(
                    "chaos-seed-success-" + UUID.randomUUID(),
                    remitterBankId, beneficiaryBankId,
                    randomAmount(),
                    "SEED-SUCCESS-" + i + "-" + System.currentTimeMillis(),
                    null,  // no decline → MATCH_FOUND → SUCCESS
                    gateway, merchantId, connectionId);
            result.add(txn);
        }

        // ── 1 × DEEMED_APPROVED ──────────────────────────────────────────
        Transaction deemed = transactionProcessingService.processNewTransaction(
                "chaos-seed-deemed-" + UUID.randomUUID(),
                remitterBankId, beneficiaryBankId,
                randomAmount(),
                "SEED-DEEMED-" + System.currentTimeMillis(),
                "NO_CONFIRMATION",  // → DEEMED_APPROVED
                gateway, merchantId, connectionId);
        result.add(deemed);

        // ── 1 × PENDING_RECONCILIATION heading toward breach ─────────────
        // Create a TECHNICAL_DECLINED transaction, then advance it to PENDING_RECON
        Transaction headingToBreach = transactionProcessingService.processNewTransaction(
                "chaos-seed-breach-" + UUID.randomUUID(),
                remitterBankId, beneficiaryBankId,
                randomAmount(),
                "SEED-BREACH-" + System.currentTimeMillis(),
                "MALFORMED_BANK_ID",  // → TECHNICAL_DECLINED
                gateway, merchantId, connectionId);

        // Advance: TECHNICAL_DECLINED → PENDING_RECONCILIATION
        TransactionState nextState = TransactionState.PENDING_RECONCILIATION;
        headingToBreach.setState(nextState);
        headingToBreach.setTatDeadline(now.plusSeconds(15)); // breach in ~15s
        headingToBreach.setPenaltyStartAt(now.plusSeconds(15 + timeConfig.getSimulatedDaySeconds()));
        transactionRepository.save(headingToBreach);
        recordTransition(headingToBreach, TransactionState.TECHNICAL_DECLINED,
                nextState, "Chaos seed — retries exhausted (simulated)", now);
        result.add(headingToBreach);

        // ── 1 × PENALTY_ACCRUING ─────────────────────────────────────────
        Transaction penaltyTxn = transactionProcessingService.processNewTransaction(
                "chaos-seed-penalty-" + UUID.randomUUID(),
                remitterBankId, beneficiaryBankId,
                randomAmount(),
                "SEED-PENALTY-" + System.currentTimeMillis(),
                "MALFORMED_BANK_ID",  // → TECHNICAL_DECLINED
                gateway, merchantId, connectionId);

        // Fast-forward through: TECHNICAL_DECLINED → PENDING_RECON → TAT_BREACHED → PENALTY_ACCRUING
        penaltyTxn.setState(TransactionState.PENDING_RECONCILIATION);
        OffsetDateTime pastDeadline = now.minusSeconds(timeConfig.getSimulatedDaySeconds());
        penaltyTxn.setTatDeadline(pastDeadline);
        penaltyTxn.setPenaltyStartAt(now.minusSeconds(5)); // penalty started 5s ago
        transactionRepository.save(penaltyTxn);
        recordTransition(penaltyTxn, TransactionState.TECHNICAL_DECLINED,
                TransactionState.PENDING_RECONCILIATION, "Chaos seed — retries exhausted", now);

        penaltyTxn.setState(TransactionState.TAT_BREACHED);
        transactionRepository.save(penaltyTxn);
        recordTransition(penaltyTxn, TransactionState.PENDING_RECONCILIATION,
                TransactionState.TAT_BREACHED, "Chaos seed — TAT breached", now);

        penaltyTxn.setState(TransactionState.PENALTY_ACCRUING);
        penaltyTxn.setPenaltyAmountInr(new BigDecimal("100.00"));
        transactionRepository.save(penaltyTxn);
        recordTransition(penaltyTxn, TransactionState.TAT_BREACHED,
                TransactionState.PENALTY_ACCRUING, "Chaos seed — penalty clock started", now);
        result.add(penaltyTxn);

        log.info("🌱 Chaos seed-demo-data — created {} transactions for merchant={}",
                result.size(), merchantId);

        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private BigDecimal randomAmount() {
        // Random amount between ₹100 and ₹10,000
        double amount = 100 + (Math.random() * 9900);
        return BigDecimal.valueOf(amount).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private void recordTransition(Transaction txn,
                                  TransactionState from,
                                  TransactionState to,
                                  String reason,
                                  OffsetDateTime at) {
        StateTransition st = StateTransition.builder()
                .transaction(txn)
                .fromState(from != null ? from.name() : null)
                .toState(to.name())
                .transitionedAt(at)
                .reason(reason)
                .build();
        stateTransitionRepository.save(st);
    }
}
