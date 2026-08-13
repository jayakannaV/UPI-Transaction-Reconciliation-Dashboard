-- Backfill external_payment_ref from idempotency_key for real gateway transactions
-- where external_payment_ref is currently null.
UPDATE transactions 
SET external_payment_ref = idempotency_key 
WHERE source_gateway != 'simulated' 
  AND source_gateway IS NOT NULL 
  AND external_payment_ref IS NULL;
