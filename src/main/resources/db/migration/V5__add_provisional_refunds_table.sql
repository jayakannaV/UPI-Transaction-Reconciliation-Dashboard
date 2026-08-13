-- V5__add_provisional_refunds_table.sql
-- Provisional Recovery Ledger — tracks merchant out-of-pocket refunds
-- and their recovery status when the bank eventually reverses.

CREATE TABLE provisional_refunds (
    id                          BIGSERIAL PRIMARY KEY,
    txn_id                      UUID NOT NULL REFERENCES transactions(txn_id),
    amount_refunded_by_merchant NUMERIC(12,2) NOT NULL,
    refunded_at                 TIMESTAMPTZ NOT NULL,
    recovery_status             TEXT NOT NULL DEFAULT 'PENDING_FROM_BANK'
);

CREATE INDEX idx_prov_refunds_txn ON provisional_refunds(txn_id);
CREATE INDEX idx_prov_refunds_status ON provisional_refunds(recovery_status);
