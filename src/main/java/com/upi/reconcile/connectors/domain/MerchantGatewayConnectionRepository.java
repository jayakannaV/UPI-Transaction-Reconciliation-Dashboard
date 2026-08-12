package com.upi.reconcile.connectors.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MerchantGatewayConnectionRepository
        extends JpaRepository<MerchantGatewayConnection, UUID> {

    /** List all connections (any status) for a merchant. */
    List<MerchantGatewayConnection> findByMerchant_MerchantId(UUID merchantId);

    /** Find the ACTIVE connection for a specific merchant + gateway. */
    Optional<MerchantGatewayConnection> findByMerchant_MerchantIdAndGatewayAndStatus(
            UUID merchantId, String gateway, String status);

    /** Find a specific connection owned by a merchant. */
    Optional<MerchantGatewayConnection> findByConnectionIdAndMerchant_MerchantId(
            UUID connectionId, UUID merchantId);
}
