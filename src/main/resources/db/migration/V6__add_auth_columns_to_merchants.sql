-- V6__add_auth_columns_to_merchants.sql
-- Extends the merchants table with login identity columns.
-- email + password_hash enable JWT-based stateless authentication.
-- business_name is an optional display label.
--
-- Uses DEFAULT '' so the migration is backward-compatible with any
-- pre-existing rows that were inserted before auth was added.

ALTER TABLE merchants ADD COLUMN email         TEXT NOT NULL DEFAULT '';
ALTER TABLE merchants ADD COLUMN password_hash  TEXT NOT NULL DEFAULT '';
ALTER TABLE merchants ADD COLUMN business_name  TEXT;

CREATE UNIQUE INDEX idx_merchants_email ON merchants(email) WHERE email <> '';
