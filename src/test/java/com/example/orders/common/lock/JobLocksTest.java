package com.example.orders.common.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.orders.support.Concurrently;

/**
 * Job locks against a real database (H2), with one {@link JobLocks} per simulated instance. Each test
 * uses its own job row, so the seeded rows and other test classes are untouched.
 */
@SpringBootTest
class JobLocksTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(5);

    @Autowired
    private JdbcTemplate jdbc;

    private String job;

    @BeforeEach
    void newJob() {
        job = "test-" + UUID.randomUUID();
        jdbc.update("INSERT INTO job_locks (name, locked_until) VALUES (?, ?)", job, Timestamp.from(T0.minusSeconds(1)));
    }

    private JobLocks instance(String name, Instant now) {
        return new JobLocks(jdbc, Clock.fixed(now, ZoneOffset.UTC), LEASE, name);
    }

    @Test
    void theAppsJobsHaveTheirRows() {
        assertThat(jdbc.queryForList("SELECT name FROM job_locks WHERE name NOT LIKE 'test-%'", String.class))
                .containsExactlyInAnyOrder("provisioning-sweep", "idempotency-key-purge");
    }

    @Test
    void onlyOneInstanceHoldsAJobAtATime() {
        assertThat(instance("A", T0).tryAcquire(job)).isTrue();
        assertThat(instance("B", T0.plusSeconds(10)).tryAcquire(job)).isFalse();
        assertThat(instance("A", T0.plusSeconds(10)).tryAcquire(job)).as("the holder may renew").isTrue();
    }

    @Test
    void aLockWhoseHolderDiedIsFreeOnceItsLeaseRunsOut() {
        instance("A", T0).tryAcquire(job);

        assertThat(instance("B", T0.plus(LEASE).minusSeconds(1)).tryAcquire(job)).isFalse();
        assertThat(instance("B", T0.plus(LEASE)).tryAcquire(job)).isTrue();
    }

    @Test
    void onlyTheHolderCanReleaseALock() {
        instance("A", T0).tryAcquire(job);

        instance("B", T0).release(job);
        assertThat(instance("B", T0).tryAcquire(job)).as("B can't free A's lock").isFalse();

        instance("A", T0).release(job);
        assertThat(instance("B", T0).tryAcquire(job)).as("free at once after A releases it").isTrue();
    }

    @Test
    void runExclusivelySkipsTheTaskWhileAnotherInstanceRunsTheJob() {
        instance("A", T0).tryAcquire(job);
        AtomicInteger runs = new AtomicInteger();

        assertThat(instance("B", T0).runExclusively(job, runs::incrementAndGet)).isFalse();
        assertThat(runs).hasValue(0);
    }

    @Test
    void runExclusivelyFreesTheLockEvenIfTheTaskThrows() {
        assertThatThrownBy(() -> instance("A", T0).runExclusively(job, () -> {
            throw new IllegalStateException("boom");
        })).hasMessage("boom");

        assertThat(instance("B", T0).tryAcquire(job)).isTrue();
    }

    @Test
    void ofManyInstancesRacingForAJobExactlyOneGetsIt() throws Exception {
        List<Boolean> acquired = Concurrently.run(8, i -> () -> instance("instance-" + i, T0).tryAcquire(job));

        assertThat(acquired).containsOnlyOnce(true);
    }

    @Test
    void aJobWithoutARowIsAProgrammingError() {
        assertThatThrownBy(() -> instance("A", T0).tryAcquire("no-such-job"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no-such-job");
    }
}
