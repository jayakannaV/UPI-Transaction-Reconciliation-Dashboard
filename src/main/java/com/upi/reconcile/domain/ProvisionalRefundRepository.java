package com.upi.reconcile.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Repository
public interface ProvisionalRefundRepository extends JpaRepository<ProvisionalRefund, Long> {

    List<ProvisionalRefund> findByTransaction_TxnId(UUID txnId);

    List<ProvisionalRefund> findByTransaction_TxnIdAndRecoveryStatus(UUID txnId, RecoveryStatus status);

    List<ProvisionalRefund> findByRecoveryStatus(RecoveryStatus status);

    long countByRecoveryStatus(RecoveryStatus status);

    /**
     * Sums the {@code amount_refunded_by_merchant} for all provisional refunds
     * with the given recovery status. Returns null if no rows match.
     */
    @Query("SELECT COALESCE(SUM(pr.amountRefundedByMerchant), 0) FROM ProvisionalRefund pr WHERE pr.recoveryStatus = :status")
    BigDecimal sumAmountByRecoveryStatus(RecoveryStatus status);
}
