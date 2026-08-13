package com.upi.reconcile.api;

import com.upi.reconcile.connectors.domain.MerchantGatewayConnection;
import com.upi.reconcile.connectors.domain.MerchantGatewayConnectionRepository;
import com.upi.reconcile.domain.ProvisionalRefund;
import com.upi.reconcile.domain.ProvisionalRefundRepository;
import com.upi.reconcile.domain.ProvisionalRefundService;
import com.upi.reconcile.domain.StateTransition;
import com.upi.reconcile.domain.StateTransitionRepository;
import com.upi.reconcile.domain.Transaction;
import com.upi.reconcile.domain.TransactionRepository;
import com.upi.reconcile.domain.TransactionState;
import com.upi.reconcile.security.MerchantContextHolder;
import com.upi.reconcile.domain.StateMachine;
import com.upi.reconcile.domain.TransactionEvent;
import com.upi.reconcile.domain.TransactionStateChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Transaction query and action endpoints — ARCHITECTURE.md §7.
 * <p>
 * GET /api/transactions?state=&amp;bank_id=&amp;page=
 * GET /api/transactions/:txn_id/history
 * POST /api/transactions/:txn_id/generate-complaint
 */
@Slf4j
@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class TransactionController {

        private static final int PAGE_SIZE = 20;

        /** RBI circular reference per ARCHITECTURE.md §7. */
        private static final String RBI_CIRCULAR_REF = "DPSS.CO.PD No.629/02.01.014/2019-20";

        private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm:ss XXX");

        private static final DateTimeFormatter DATE_ONLY_FMT = DateTimeFormatter.ofPattern("dd-MMM-yyyy");

        private final TransactionRepository transactionRepository;
        private final StateTransitionRepository stateTransitionRepository;
        private final ProvisionalRefundService provisionalRefundService;
        private final ProvisionalRefundRepository provisionalRefundRepository;
        private final MerchantGatewayConnectionRepository connectionRepository;
        private final StateMachine stateMachine;
        private final ApplicationEventPublisher eventPublisher;

        // ── GET /api/transactions ─────────────────────────────────────

        @GetMapping
        public ResponseEntity<Page<TransactionDto>> listTransactions(
                        @RequestParam(required = false) String state,
                        @RequestParam(name = "bank_id", required = false) UUID bankId,
                        @RequestParam(defaultValue = "0") int page) {

                log.info("List transactions — state={}, bankId={}, page={}", state, bankId, page);

                Specification<Transaction> spec = Specification.where(null);

                // Tenant isolation: only show the authenticated merchant's transactions
                UUID merchantId = MerchantContextHolder.currentMerchantId();
                spec = spec.and(TransactionSpecifications.belongsToMerchant(merchantId));

                if (state != null && !state.isBlank()) {
                        TransactionState txnState = TransactionState.valueOf(state.trim().toUpperCase());
                        spec = spec.and(TransactionSpecifications.hasState(txnState));
                }
                if (bankId != null) {
                        spec = spec.and(TransactionSpecifications.involvesBankId(bankId));
                }

                PageRequest pageable = PageRequest.of(page, PAGE_SIZE, Sort.by(Sort.Direction.DESC, "createdAt"));
                Page<Transaction> txnPage = transactionRepository.findAll(spec, pageable);

                // Batch-load connections for all transactions on this page (avoids N+1)
                Map<UUID, MerchantGatewayConnection> connectionMap = batchLoadConnections(txnPage.getContent());

                Page<TransactionDto> result = txnPage.map(txn -> toDto(txn, connectionMap));

                return ResponseEntity.ok(result);
        }

        // ── GET /api/transactions/{txnId}/history ─────────────────────

        @GetMapping("/{txnId}/history")
        public ResponseEntity<List<StateTransitionDto>> getTransactionHistory(
                        @PathVariable UUID txnId) {

                log.info("Get history for txn {}", txnId);

                if (transactionRepository.findById(txnId).isEmpty()) {
                        return ResponseEntity.notFound().build();
                }

                // Ownership check
                Transaction txn = transactionRepository.findById(txnId).get();
                UUID merchantId = MerchantContextHolder.currentMerchantId();
                if (txn.getMerchantOwner() == null ||
                        !txn.getMerchantOwner().getMerchantId().equals(merchantId)) {
                        return ResponseEntity.notFound().build();
                }

                List<StateTransitionDto> history = stateTransitionRepository
                                .findByTransaction_TxnIdOrderByTransitionedAtAsc(txnId)
                                .stream()
                                .map(this::toHistoryDto)
                                .toList();

                return ResponseEntity.ok(history);
        }

        // ── POST /api/transactions/{txnId}/generate-complaint ─────────

        /** States that are eligible for complaint generation (Type 2 only). */
        private static final java.util.Set<TransactionState> COMPLAINT_ELIGIBLE_STATES = java.util.Set
                        .of(TransactionState.PENALTY_ACCRUING, TransactionState.ESCALATED);

        @PostMapping("/{txnId}/generate-complaint")
        @Transactional(readOnly = true)
        public ResponseEntity<Map<String, String>> generateComplaint(
                        @PathVariable UUID txnId) {

                log.info("Generate complaint for txn {}", txnId);

                Transaction txn = transactionRepository.findById(txnId).orElse(null);
                if (txn == null) {
                        return ResponseEntity.notFound().build();
                }

                // Ownership check
                UUID merchantId = MerchantContextHolder.currentMerchantId();
                if (txn.getMerchantOwner() == null ||
                        !txn.getMerchantOwner().getMerchantId().equals(merchantId)) {
                        return ResponseEntity.notFound().build();
                }

                // Guard: only PENALTY_ACCRUING and ESCALATED are complaint-eligible
                if (!COMPLAINT_ELIGIBLE_STATES.contains(txn.getState())) {
                        return ResponseEntity.badRequest()
                                        .body(Map.of("error",
                                                        "Complaint generation is only available for transactions in "
                                                                        + COMPLAINT_ELIGIBLE_STATES + " states"));
                }



                // Look up any provisional refund the merchant fronted
                List<ProvisionalRefund> provisionalRefunds = provisionalRefundRepository.findByTransaction_TxnId(txnId);
                ProvisionalRefund provisionalRefund = provisionalRefunds.isEmpty() ? null
                                : provisionalRefunds.getFirst();

                String complaint = buildComplaintText(txn, provisionalRefund);

                // Look up the beneficiary bank's grievance email (complaints target the beneficiary)
                String grievanceEmail = txn.getBeneficiaryBank() != null
                        ? txn.getBeneficiaryBank().getGrievanceEmail()
                        : null;

                java.util.Map<String, String> response = new java.util.LinkedHashMap<>();
                response.put("complaint", complaint);
                if (grievanceEmail != null) {
                        response.put("grievanceEmail", grievanceEmail);
                }
                return ResponseEntity.ok(response);
        }

        // ── POST /api/transactions/{txnId}/mark-penalty-received ─────────

        @PostMapping("/{txnId}/mark-penalty-received")
        @Transactional
        public ResponseEntity<?> markPenaltyReceived(@PathVariable UUID txnId) {
                log.info("Mark penalty received manually for txn {}", txnId);

                Transaction txn = transactionRepository.findById(txnId).orElse(null);
                if (txn == null) {
                        return ResponseEntity.notFound().build();
                }

                UUID merchantId = MerchantContextHolder.currentMerchantId();
                if (txn.getMerchantOwner() == null ||
                        !txn.getMerchantOwner().getMerchantId().equals(merchantId)) {
                        return ResponseEntity.notFound().build();
                }

                if (txn.getState() != TransactionState.PENALTY_ACCRUING && txn.getState() != TransactionState.ESCALATED) {
                        return ResponseEntity.badRequest()
                                .body(Map.of("error", "Only PENALTY_ACCRUING or ESCALATED transactions can be manually reconciled."));
                }

                TransactionState nextState = stateMachine.transition(txn.getState(), TransactionEvent.MANUAL_PENALTY_RECEIVED);

                StateTransition transition = new StateTransition();
                transition.setTransaction(txn);
                transition.setFromState(txn.getState().name());
                transition.setToState(nextState.name());
                transition.setTransitionedAt(OffsetDateTime.now());
                transition.setReason("Offline penalty settlement received by merchant");

                txn.setState(nextState);
                txn.setResolvedAt(OffsetDateTime.now());

                transactionRepository.save(txn);
                stateTransitionRepository.save(transition);

                // Publish websocket event
                eventPublisher.publishEvent(new TransactionStateChangedEvent(
                        this,
                        txn.getTxnId(),
                        TransactionState.valueOf(transition.getFromState()),
                        nextState,
                        txn.getPenaltyAmountInr(),
                        txn.getRemitterBank() != null ? txn.getRemitterBank().getBankId() : null,
                        txn.getBeneficiaryBank() != null ? txn.getBeneficiaryBank().getBankId() : null,
                        transition.getTransitionedAt(),
                        merchantId,
                        txn.getConnectionId(),
                        transition.getReason()
                ));

                return ResponseEntity.ok(Map.of("message", "Penalty successfully marked as received."));
        }

        // ── POST /api/transactions/{txnId}/provisional-refund ─────────

        @PostMapping("/{txnId}/provisional-refund")
        public ResponseEntity<?> createProvisionalRefund(
                        @PathVariable UUID txnId,
                        @Valid @RequestBody ProvisionalRefundRequest request) {

                log.info("POST provisional-refund for txn {} — amount={}",
                                txnId, request.getAmountRefundedByMerchant());

                try {
                        ProvisionalRefund refund = provisionalRefundService
                                        .createProvisionalRefund(txnId, request.getAmountRefundedByMerchant());

                        ProvisionalRefundDto dto = ProvisionalRefundDto.builder()
                                        .id(refund.getId())
                                        .txnId(txnId)
                                        .amountRefundedByMerchant(refund.getAmountRefundedByMerchant())
                                        .refundedAt(refund.getRefundedAt())
                                        .recoveryStatus(refund.getRecoveryStatus().name())
                                        .build();

                        return ResponseEntity.status(HttpStatus.CREATED).body(dto);

                } catch (IllegalArgumentException e) {
                        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                        .body(Map.of("error", e.getMessage()));
                } catch (IllegalStateException e) {
                        // Could be ineligible state (400) or duplicate (409)
                        HttpStatus status = e.getMessage().contains("already exists")
                                        ? HttpStatus.CONFLICT
                                        : HttpStatus.BAD_REQUEST;
                        return ResponseEntity.status(status)
                                        .body(Map.of("error", e.getMessage()));
                }
        }

        // ── Mapping helpers ───────────────────────────────────────────

        /**
         * Batch-loads all {@link MerchantGatewayConnection}s referenced by the
         * transactions on the current page. Returns a map keyed by connectionId.
         */
        private Map<UUID, MerchantGatewayConnection> batchLoadConnections(List<Transaction> transactions) {
                List<UUID> connectionIds = transactions.stream()
                                .map(Transaction::getConnectionId)
                                .filter(Objects::nonNull)
                                .distinct()
                                .toList();
                if (connectionIds.isEmpty()) {
                        return Collections.emptyMap();
                }
                return connectionRepository.findAllById(connectionIds).stream()
                                .collect(Collectors.toMap(
                                                MerchantGatewayConnection::getConnectionId,
                                                Function.identity()));
        }

        private TransactionDto toDto(Transaction txn, Map<UUID, MerchantGatewayConnection> connectionMap) {
                // Resolve gateway + connection status from the connection map
                String gateway = "simulated";
                String connectionStatus = null;
                if (txn.getConnectionId() != null) {
                        MerchantGatewayConnection conn = connectionMap.get(txn.getConnectionId());
                        if (conn != null) {
                                gateway = conn.getGateway();
                                connectionStatus = conn.getStatus();
                        }
                }

                return TransactionDto.builder()
                                .txnId(txn.getTxnId())
                                .state(txn.getState().name())
                                .amountInr(txn.getAmountInr())
                                .penaltyAmountInr(txn.getPenaltyAmountInr())
                                .remitterBankId(txn.getRemitterBank() != null ? txn.getRemitterBank().getBankId()
                                                : null)
                                .remitterBankName(
                                                txn.getRemitterBank() != null ? txn.getRemitterBank().getName() : null)
                                .beneficiaryBankId(
                                                txn.getBeneficiaryBank() != null ? txn.getBeneficiaryBank().getBankId()
                                                                : null)
                                .beneficiaryBankName(
                                                txn.getBeneficiaryBank() != null ? txn.getBeneficiaryBank().getName()
                                                                : null)
                                .createdAt(txn.getCreatedAt())
                                .tatDeadline(txn.getTatDeadline())
                                .penaltyStartAt(txn.getPenaltyStartAt())
                                .resolvedAt(txn.getResolvedAt())
                                .declineCode(txn.getDeclineCode())
                                .orderReference(txn.getOrderReference())
                                .mlClassification(txn.getMlClassification())
                                .mlConfidence(txn.getMlConfidence())
                                .sourceGateway(txn.getSourceGateway())
                                .gateway(gateway)
                                .connectionStatus(connectionStatus)
                                .resolutionReason(txn.getResolutionReason())
                                .build();
        }

        private StateTransitionDto toHistoryDto(StateTransition st) {
                return StateTransitionDto.builder()
                                .fromState(st.getFromState())
                                .toState(st.getToState())
                                .transitionedAt(st.getTransitionedAt())
                                .reason(st.getReason())
                                .build();
        }

        // ── Complaint template ────────────────────────────────────────

        /**
         * Deterministic complaint text generator — no LLM, no external call.
         * Fills a fixed Ombudsman complaint template with transaction details,
         * scoped to Type 2 (bank/NPCI-side) stuck payments only.
         *
         * <p>
         * Template order per spec:
         * <ol>
         * <li>Transaction ID</li>
         * <li>Original transaction date</li>
         * <li>T+1 deadline date</li>
         * <li>Number of days breached</li>
         * <li>Computed penalty_amount_inr</li>
         * <li>RBI circular reference</li>
         * <li>Merchant's provisional refund details (if any)</li>
         * <li>Closing statement requesting resolution and compensation to merchant</li>
         * </ol>
         *
         * @param txn               the stuck transaction
         * @param provisionalRefund the merchant's provisional refund, or null if none
         */
        String buildComplaintText(Transaction txn, ProvisionalRefund provisionalRefund) {
                String txnDate = txn.getCreatedAt() != null
                                ? txn.getCreatedAt().format(DATE_FMT)
                                : "N/A";
                String deadlineDate = txn.getTatDeadline() != null
                                ? txn.getTatDeadline().format(DATE_FMT)
                                : "N/A";

                // Compute days breached: from TAT deadline to resolvedAt (or now)
                long daysBreached = 0;
                if (txn.getTatDeadline() != null) {
                        OffsetDateTime endPoint = txn.getResolvedAt() != null
                                        ? txn.getResolvedAt()
                                        : OffsetDateTime.now();
                        daysBreached = Math.max(0,
                                        ChronoUnit.DAYS.between(txn.getTatDeadline(), endPoint));
                }

                String penaltyAmount = txn.getPenaltyAmountInr() != null
                                ? txn.getPenaltyAmountInr().toPlainString()
                                : "0";
                String txnAmount = txn.getAmountInr() != null
                                ? txn.getAmountInr().toPlainString()
                                : "N/A";

                // Build provisional refund section (only if merchant fronted a refund)
                String provisionalRefundSection = "";
                if (provisionalRefund != null) {
                        String refundDate = provisionalRefund.getRefundedAt() != null
                                        ? provisionalRefund.getRefundedAt().format(DATE_ONLY_FMT)
                                        : "N/A";
                        String refundAmount = provisionalRefund.getAmountRefundedByMerchant() != null
                                        ? provisionalRefund.getAmountRefundedByMerchant().toPlainString()
                                        : "N/A";

                        provisionalRefundSection = """

                                        Merchant's Provisional Refund:
                                          The merchant has already made the customer whole out of their own
                                          funds. Details of the provisional refund issued by the merchant:

                                          Amount Refunded by Merchant : ₹%s
                                          Date of Merchant Refund     : %s

                                          Since the merchant has personally borne the cost of refunding the
                                          customer, the bank's compensation/reversal amount of ₹%s must be
                                          paid directly to the merchant, not to the customer (who has already
                                          been made whole). This provisional refund is documented in the
                                          merchant's auditable ledger and constitutes a legitimate claim for
                                          reimbursement.
                                        """.formatted(refundAmount, refundDate, txnAmount);
                }

                return """
                                COMPLAINT TO THE BANKING OMBUDSMAN
                                ==================================

                                Subject: Non-adherence to RBI-mandated Turn Around Time (TAT)
                                         for UPI transaction resolution — Type 2 (Bank/NPCI-side
                                         stuck payment)

                                1. Transaction Details:
                                   Transaction ID       : %s
                                   Original Txn Date    : %s
                                   Amount (INR)         : ₹%s
                                   Current State        : %s

                                2. TAT Breach Details:
                                   T+1 Deadline Date    : %s
                                   Days Breached        : %d day(s)

                                3. Compensation Claimed:
                                   As per RBI guidelines, the beneficiary bank is liable to pay
                                   ₹100 per day of delay beyond the mandated TAT (T+1 working day)
                                   for UPI credit-not-received complaints.

                                   Computed Penalty     : ₹%s

                                4. Regulatory Reference:
                                   RBI/2019-20/67, %s
                                   ("Harmonisation of Turn Around Time (TAT) and Customer Compensation
                                    for Failed Transactions using Authorised Payment Systems")
                                %s
                                Resolution Request:
                                  The above transaction has breached the prescribed TAT without
                                  resolution. I hereby request the Hon'ble Banking Ombudsman to
                                  direct the concerned bank to:
                                    (a) Immediately resolve the stuck transaction;
                                    (b) Credit the computed compensation amount of ₹%s to the
                                        merchant's account as mandated by the above RBI circular.

                                This complaint is auto-generated from auditable system records
                                and is based on deterministic, verifiable transaction data.
                                """.formatted(
                                txn.getTxnId(),
                                txnDate,
                                txnAmount,
                                txn.getState().name(),
                                deadlineDate,
                                daysBreached,
                                penaltyAmount,
                                RBI_CIRCULAR_REF,
                                provisionalRefundSection,
                                penaltyAmount);
        }
}
