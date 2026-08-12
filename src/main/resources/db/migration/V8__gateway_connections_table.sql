-- V8__gateway_connections_table.sql
-- Extracts gateway connection credentials from merchants into a dedicated
-- merchant_gateway_connections table, enabling multi-gateway support and
-- disconnect/reconnect lifecycle.

-- 1. Create new table
CREATE TABLE merchant_gateway_connections (
    connection_id        UUID PRIMARY KEY,
    merchant_id          UUID NOT NULL REFERENCES merchants(merchant_id),
    gateway              TEXT NOT NULL,
    encrypted_api_key    TEXT NOT NULL,
    encrypted_api_secret TEXT NOT NULL,
    webhook_secret       TEXT,
    status               TEXT NOT NULL DEFAULT 'ACTIVE',
    connected_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    disconnected_at      TIMESTAMPTZ
);

-- Partial unique index: only one ACTIVE connection per (merchant, gateway).
-- DISCONNECTED rows are excluded so historical records don't conflict.
CREATE UNIQUE INDEX uq_merchant_gateway_active
    ON merchant_gateway_connections(merchant_id, gateway)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_mgc_merchant ON merchant_gateway_connections(merchant_id);
CREATE INDEX idx_mgc_status   ON merchant_gateway_connections(status);

-- 2. Migrate existing data (rows where connected_gateway is non-empty)
INSERT INTO merchant_gateway_connections (
    connection_id, merchant_id, gateway,
    encrypted_api_key, encrypted_api_secret, webhook_secret,
    status, connected_at
)
SELECT
    gen_random_uuid(), merchant_id, connected_gateway,
    encrypted_api_key, encrypted_api_secret, webhook_secret,
    'ACTIVE', created_at
FROM merchants
WHERE connected_gateway IS NOT NULL AND connected_gateway <> '';

-- 3. Add connection_id FK to transactions for traceability
ALTER TABLE transactions ADD COLUMN connection_id UUID
    REFERENCES merchant_gateway_connections(connection_id);

-- 4. Drop gateway columns from merchants (now live on connections table)
ALTER TABLE merchants DROP COLUMN connected_gateway;
ALTER TABLE merchants DROP COLUMN encrypted_api_key;
ALTER TABLE merchants DROP COLUMN encrypted_api_secret;
ALTER TABLE merchants DROP COLUMN webhook_secret;

-- 5. Drop the index on the now-removed connected_gateway column
DROP INDEX IF EXISTS idx_merchants_gateway;
