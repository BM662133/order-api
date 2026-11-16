package com.example.orders.order.persistence;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRecordRepository extends Repository<IdempotencyRecord, Long> {

    /** Flushes immediately, so a duplicate key surfaces from this call, not at commit. */
    IdempotencyRecord saveAndFlush(IdempotencyRecord record);

    Optional<IdempotencyRecord> findByPartnerIdAndIdempotencyKey(String partnerId, String idempotencyKey);

    /** Frees one expired key so it can be reused; run in the transaction that reuses it. */
    @Modifying
    @Query("delete from IdempotencyRecord r where r.partnerId = :partnerId and r.idempotencyKey = :key "
            + "and r.expiresAt <= :now")
    int deleteExpired(@Param("partnerId") String partnerId, @Param("key") String key, @Param("now") Instant now);

    /** Housekeeping across all partners (a delete, never a read, so tenant scoping doesn't apply). */
    @Modifying
    @Query("delete from IdempotencyRecord r where r.expiresAt <= :now")
    int deleteAllExpired(@Param("now") Instant now);
}
