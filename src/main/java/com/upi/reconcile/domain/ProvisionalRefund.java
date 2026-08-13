package com.upi.reconcile.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Provisional refund entity — maps to the {@code provisional_refunds} table.
 *
 * <p>Tracks a refund that a merchant issued out-of-pocket to a customer
 * while the original UPI transaction is still unresolved. When the bank
 * eventually reverses the transaction, the {@link #recoveryStatus} is
 * automatically flipped from {@code PENDING_FROM_BANK} to {@code RECOVERED}.
 */
@Entity
@Table(name = "provisional_refunds")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProvisionalRefund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "txn_id", nullable = false)
    private Transaction transaction;

    @Column(name = "amount_refunded_by_merchant", precision = 12, scale = 2, nullable = false)
    private BigDecimal amountRefundedByMerchant;

    @Column(name = "refunded_at", nullable = false)
    private OffsetDateTime refundedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "recovery_status", nullable = false)
    @Builder.Default
    private RecoveryStatus recoveryStatus = RecoveryStatus.PENDING_FROM_BANK;
}
