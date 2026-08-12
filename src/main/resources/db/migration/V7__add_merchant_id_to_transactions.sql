-- V7__add_merchant_id_to_transactions.sql
-- Adds a nullable merchant_id FK to the transactions table so each
-- transaction can be associated with its owning merchant for tenant isolation.
-- Nullable because generic webhook transactions (non-connector path) have no merchant context.

ALTER TABLE transactions ADD COLUMN merchant_id UUID REFERENCES merchants(merchant_id);

CREATE INDEX idx_transactions_merchant ON transactions(merchant_id);
