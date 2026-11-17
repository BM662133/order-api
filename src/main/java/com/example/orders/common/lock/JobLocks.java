package com.example.orders.common.lock;

import java.lang.management.ManagementFactory;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Keeps a background job from running on two instances at the same time when several share the database.
 * Each job has a row in {@code job_locks} (added by a migration); a run leases it with one conditional
 * UPDATE, which the database applies to one instance at a time, and frees it when done.
 *
 * <p>Only overlap is prevented: with N instances a job may run up to N times per interval, one after
 * another, and each run sees what the previous one committed. The lease ({@code orders.jobs.lock-lease})
 * only matters if a holder dies mid-run; it must be longer than the slowest run, or a second instance
 * may start while the first is still going. Instances compare their own clocks, so the lease must also
 * dwarf any clock skew between hosts.
 */
@Component
public class JobLocks {

    private static final Logger log = LoggerFactory.getLogger(JobLocks.class);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Duration lease;
    private final String owner;

    @Autowired
    public JobLocks(JdbcTemplate jdbc, Clock clock, @Value("${orders.jobs.lock-lease:5m}") Duration lease) {
        this(jdbc, clock, lease,
                ManagementFactory.getRuntimeMXBean().getName() + "/" + UUID.randomUUID().toString().substring(0, 8));
    }

    /** {@code owner} identifies this instance in {@code job_locks.locked_by}; it must be unique per instance. */
    public JobLocks(JdbcTemplate jdbc, Clock clock, Duration lease, String owner) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.lease = lease;
        this.owner = owner;
    }

    /**
     * Runs {@code task} if this instance gets {@code job}'s lock, and frees the lock afterwards, even if the
     * task throws.
     *
     * @return whether the task ran (false: another instance is running the job right now)
     */
    public boolean runExclusively(String job, Runnable task) {
        if (!tryAcquire(job)) {
            log.debug("Skipping job {}: another instance is running it", job);
            return false;
        }
        try {
            task.run();
            return true;
        } finally {
            release(job);
        }
    }

    /** Leases the lock if it is free (or its holder's lease ran out, or this instance already holds it). */
    public boolean tryAcquire(String job) {
        Instant now = clock.instant();
        int updated = jdbc.update("""
                UPDATE job_locks SET locked_by = ?, locked_until = ?
                WHERE name = ? AND (locked_until <= ? OR locked_by = ?)""",
                owner, Timestamp.from(now.plus(lease)), job, Timestamp.from(now), owner);
        if (updated == 1) {
            return true;
        }
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM job_locks WHERE name = ?", Integer.class, job);
        if (rows == null || rows == 0) {
            throw new IllegalStateException("No job_locks row for job '" + job + "'; add one in a migration");
        }
        return false;
    }

    /** Frees the lock, but only if this instance still holds it (its lease may have run out and moved on). */
    public void release(String job) {
        jdbc.update("UPDATE job_locks SET locked_until = ? WHERE name = ? AND locked_by = ?",
                Timestamp.from(clock.instant()), job, owner);
    }
}
