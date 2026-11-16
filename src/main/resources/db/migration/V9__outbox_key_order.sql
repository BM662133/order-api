-- Serves the relay's head-of-line check: "is there an older PENDING event with the same key?"
CREATE INDEX ix_outbox_events_key_pending ON outbox_events (message_key, status, created_at);
