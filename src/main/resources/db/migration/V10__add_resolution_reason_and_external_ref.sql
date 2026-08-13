ALTER TABLE transactions 
ADD COLUMN resolution_reason VARCHAR(1000),
ADD COLUMN external_payment_ref VARCHAR(255);
