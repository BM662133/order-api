-- When the sweeper confirmed the carrier side of an order that failed with an uncertain line state
-- (PROVISIONING_UNAVAILABLE, COMPENSATION_FAILED). Null = not yet reconciled.
ALTER TABLE orders ADD COLUMN reconciled_at TIMESTAMP WITH TIME ZONE;

-- Serve the sweeper: unfinished orders by age, and "does this order have a pending event?".
CREATE INDEX ix_orders_status_updated ON orders (status, updated_at);
CREATE INDEX ix_outbox_events_aggregate ON outbox_events (aggregate_id, status);
