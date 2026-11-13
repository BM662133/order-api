-- Added nullable, backfilled, then made NOT NULL: adding a NOT NULL column directly fails on a table
-- that already has rows, and a constant DEFAULT would violate the unique constraint below.
ALTER TABLE orders ADD COLUMN idempotency_key VARCHAR(100);
ALTER TABLE orders ADD COLUMN request_fingerprint VARCHAR(64);

UPDATE orders
SET idempotency_key     = 'legacy-' || CAST(id AS VARCHAR),
    request_fingerprint = REPEAT('0', 64)
WHERE idempotency_key IS NULL;

ALTER TABLE orders ALTER COLUMN idempotency_key SET NOT NULL;
ALTER TABLE orders ALTER COLUMN request_fingerprint SET NOT NULL;

-- Keys are scoped per partner. This index also serves the (partner_id, idempotency_key) lookup.
ALTER TABLE orders ADD CONSTRAINT uq_orders_partner_idempotency UNIQUE (partner_id, idempotency_key);
