package com.example.orders.outbox;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * Step three of a relay pass: saves what happened to the claimed events. Each event still carries the
 * version its claim gave it, so if our lease ran out and another relay re-claimed the event, our write
 * fails the version check instead of overwriting the newer owner's state.
 */
@Component
class OutboxOutcomes {

    private static final Logger log = LoggerFactory.getLogger(OutboxOutcomes.class);

    private final OutboxRepository outboxRepository;

    OutboxOutcomes(OutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    /**
     * Saves all of them in one transaction. If one is stale, that transaction rolls back and they are
     * saved one at a time instead, so only the stale ones are lost, not the whole batch.
     *
     * @return the events that were saved (stale ones left out)
     */
    List<OutboxEvent> record(List<OutboxEvent> changed) {
        if (changed.isEmpty()) {
            return List.of();
        }
        try {
            outboxRepository.saveAll(changed);
            return changed;
        } catch (OptimisticLockingFailureException batchConflict) {
            List<OutboxEvent> saved = new ArrayList<>(changed.size());
            for (OutboxEvent event : changed) {
                try {
                    outboxRepository.save(event);
                    saved.add(event);
                } catch (OptimisticLockingFailureException stale) {
                    log.warn("Outbox event {} was re-claimed by another relay after this relay's lease ran out; "
                            + "dropping this relay's outcome (the event may be delivered twice)", event.getId());
                }
            }
            return saved;
        }
    }
}
