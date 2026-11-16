package com.example.orders.order.app;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.orders.order.persistence.IdempotencyRecordRepository;

/**
 * Deletes expired idempotency keys. Housekeeping only: correctness doesn't depend on it, because
 * {@link OrderWriter#insert} frees an expired key itself when it's reused.
 */
@Component
public class IdempotencyKeyPurge {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyKeyPurge.class);

    private final IdempotencyRecordRepository idempotencyRecords;
    private final Clock clock;

    public IdempotencyKeyPurge(IdempotencyRecordRepository idempotencyRecords, Clock clock) {
        this.idempotencyRecords = idempotencyRecords;
        this.clock = clock;
    }

    /** @return how many keys were deleted */
    @Transactional
    public int purgeExpired() {
        int deleted = idempotencyRecords.deleteAllExpired(clock.instant());
        if (deleted > 0) {
            log.info("Purged {} expired idempotency key(s)", deleted);
        }
        return deleted;
    }
}
