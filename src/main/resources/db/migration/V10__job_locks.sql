-- One row per background job that must not run on two instances at the same time (see JobLocks). A job
-- runs only while its row is leased to it. The rows exist up front, so acquiring is a single UPDATE.
CREATE TABLE job_locks (
    name         VARCHAR(64)              PRIMARY KEY,
    locked_by    VARCHAR(200),
    locked_until TIMESTAMP WITH TIME ZONE NOT NULL
);

INSERT INTO job_locks (name, locked_until) VALUES
    ('provisioning-sweep',    CURRENT_TIMESTAMP),
    ('idempotency-key-purge', CURRENT_TIMESTAMP);
