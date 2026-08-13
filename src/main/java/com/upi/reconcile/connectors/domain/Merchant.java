package com.upi.reconcile.connectors.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Merchant entity — maps to the {@code merchants} table.
 *
 * <p>
 * Represents a login identity. Gateway connection credentials are stored
 * separately in {@link MerchantGatewayConnection} (one-to-many).
 */
@Entity
@Table(name = "merchants")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Merchant {

    @Id
    @Column(name = "merchant_id")
    private UUID merchantId;

    @Column(nullable = false)
    private String name;

    @Column(unique = true)
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "business_name")
    private String businessName;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
