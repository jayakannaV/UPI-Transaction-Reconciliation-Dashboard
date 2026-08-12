package com.upi.reconcile.connectors.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MerchantRepository extends JpaRepository<Merchant, UUID> {

    List<Merchant> findByConnectedGateway(String connectedGateway);

    Optional<Merchant> findByEmail(String email);

    boolean existsByEmail(String email);
}
