package com.upi.reconcile.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID>,
        JpaSpecificationExecutor<Transaction> {

    // ── Eagerly-fetched overrides (fix LazyInitializationException) ───────

    @Override
    @EntityGraph(attributePaths = {"remitterBank", "beneficiaryBank"})
    List<Transaction> findAll();

    @Override
    @EntityGraph(attributePaths = {"remitterBank", "beneficiaryBank"})
    Page<Transaction> findAll(Specification<Transaction> spec, Pageable pageable);

    // ── Existing query methods ───────────────────────────────────────────

    List<Transaction> findByState(TransactionState state);

    List<Transaction> findByStateAndSourceGateway(TransactionState state, String sourceGateway);

    List<Transaction> findByStateIn(Collection<TransactionState> states);

    List<Transaction> findByRemitterBank_BankId(UUID bankId);

    List<Transaction> findByBeneficiaryBank_BankId(UUID bankId);


    // ── Anomaly monitor queries (§5) ─────────────────────────────────────

    /**
     * Counts transactions for a given remitter bank that are in one of the
     * specified states and were created after the rolling-window cutoff.
     */
    long countByRemitterBank_BankIdAndStateInAndCreatedAtAfter(
            UUID bankId, Collection<TransactionState> states, OffsetDateTime after);

    /**
     * Counts all transactions for a given remitter bank created after the
     * rolling-window cutoff.
     */
    long countByRemitterBank_BankIdAndCreatedAtAfter(UUID bankId, OffsetDateTime after);

    // ── Chaos controller queries (demo-only) ─────────────────────────────

    Optional<Transaction> findFirstByMerchantOwner_MerchantIdAndSourceGatewayAndStateOrderByCreatedAtDesc(
            UUID merchantId, String sourceGateway, TransactionState state);

    /** Find a transaction by ID scoped to a specific merchant (ownership check). */
    @EntityGraph(attributePaths = {"remitterBank", "beneficiaryBank"})
    Optional<Transaction> findByTxnIdAndMerchantOwner_MerchantId(UUID txnId, UUID merchantId);

    /** Find a transaction by its idempotency key. */
    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    /** List all transactions belonging to a merchant. */
    @EntityGraph(attributePaths = {"remitterBank", "beneficiaryBank"})
    List<Transaction> findByMerchantOwner_MerchantId(UUID merchantId);

    /** List transactions belonging to a merchant in a given state. */
    List<Transaction> findByMerchantOwner_MerchantIdAndState(UUID merchantId, TransactionState state);
}
