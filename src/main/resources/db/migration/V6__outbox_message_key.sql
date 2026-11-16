-- The broker message key, fixed when the event is recorded. Order events use partner_id:msisdn so that
-- every order for one MSISDN lands on the same Kafka partition and is validated serially.
ALTER TABLE outbox_events ADD COLUMN message_key VARCHAR(200);

UPDATE outbox_events
SET message_key = CAST(aggregate_id AS VARCHAR)
WHERE message_key IS NULL;

ALTER TABLE outbox_events ALTER COLUMN message_key SET NOT NULL;
