-- Why an order FAILED (null otherwise), and when a client asked to cancel it mid-provisioning.
ALTER TABLE orders ADD COLUMN failure_reason VARCHAR(64);
ALTER TABLE orders ADD COLUMN cancel_requested_at TIMESTAMP WITH TIME ZONE;

-- Transactional outbox: rows are inserted in the same transaction as the change they describe and
-- delivered at least once by OutboxRelay.
CREATE TABLE outbox_events (
    id              UUID                     PRIMARY KEY,
    aggregate_id    UUID                     NOT NULL,
    partner_id      VARCHAR(64)              NOT NULL,
    event_type      VARCHAR(64)              NOT NULL,
    payload         VARCHAR(4000)            NOT NULL,
    status          VARCHAR(16)              NOT NULL,
    attempts        INT                      NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_error      VARCHAR(1000),
    version         BIGINT                   NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    processed_at    TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_outbox_events_status CHECK (status IN ('PENDING', 'PROCESSED', 'DEAD'))
);

-- Serves the relay's "PENDING and due" poll. (On Postgres: a partial index WHERE status = 'PENDING'.)
CREATE INDEX ix_outbox_events_due ON outbox_events (status, next_attempt_at);
