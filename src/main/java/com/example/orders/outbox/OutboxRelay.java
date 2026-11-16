package com.example.orders.outbox;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

/**
 * Delivers due outbox events through the {@link OutboxTransport}, at least once, retrying failures with
 * backoff. Deliberately not transactional while delivering: delivery may do network I/O (handlers, a
 * broker).
 *
 * <p>What "delivered" means depends on the transport: in-process, the handler finished; with Kafka, the
 * broker acknowledged the record (the listener then runs the handler with its own retries).
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outboxRepository;
    private final OutboxTransport transport;
    private final OutboxProperties properties;
    private final Clock clock;

    public OutboxRelay(OutboxRepository outboxRepository, OutboxTransport transport, OutboxProperties properties,
            Clock clock) {
        this.outboxRepository = outboxRepository;
        this.transport = transport;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Delivers one batch of due events (oldest first, at most one per message key) and records each
     * outcome in a single transaction. A failure is recorded on its own event and does not affect the
     * others.
     *
     * @return how many events were attempted (delivered or failed; not counting ones the transport skipped)
     */
    public int relayDue() {
        List<OutboxEvent> due = outboxRepository.findDue(OutboxStatus.PENDING, clock.instant(),
                Limit.of(properties.batchSize()));
        if (due.isEmpty()) {
            return 0;
        }
        List<DeliveryResult> results = transport.deliverAll(due.stream().map(OutboxEvent::toEnvelope).toList());
        if (results.size() != due.size()) {
            throw new IllegalStateException("Transport returned " + results.size() + " results for " + due.size()
                    + " events");
        }

        List<OutboxEvent> changed = new ArrayList<>();
        List<OutboxEvent> died = new ArrayList<>();
        int attempted = 0;
        for (int i = 0; i < due.size(); i++) {
            OutboxEvent event = due.get(i);
            switch (results.get(i)) {
                case DeliveryResult.Delivered delivered -> {
                    event.markProcessed(clock);
                    changed.add(event);
                    attempted++;
                }
                case DeliveryResult.Failed failed -> {
                    if (recordFailure(event, failed.error())) {
                        died.add(event);
                    }
                    changed.add(event);
                    attempted++;
                }
                case DeliveryResult.NotAttempted notAttempted -> {
                    // left exactly as it was: no attempt counted, still due
                }
            }
        }
        outboxRepository.saveAll(changed);
        died.forEach(this::notifyDead);
        return attempted;
    }

    /** @return whether the event is now DEAD and its transport must be told (after saving) */
    private boolean recordFailure(OutboxEvent event, RuntimeException ex) {
        if (ex instanceof UnknownEventTypeException) {
            event.recordFailure(ex.getMessage(), Duration.ZERO, clock);
            event.markDead();
            log.error("Outbox event {} has unknown type {}; marked DEAD", event.getId(), event.getEventType());
            return false;
        }
        event.recordFailure(ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                properties.backoffAfter(event.getAttempts() + 1), clock);
        if (event.getAttempts() < properties.maxAttempts()) {
            log.warn("Outbox event {} ({}) failed, attempt {}/{}; retrying at {}: {}", event.getId(),
                    event.getEventType(), event.getAttempts(), properties.maxAttempts(), event.getNextAttemptAt(),
                    ex.toString());
            return false;
        }
        event.markDead();
        log.error("Outbox event {} ({}) failed {} times; marked DEAD", event.getId(), event.getEventType(),
                event.getAttempts(), ex);
        return true;
    }

    private void notifyDead(OutboxEvent event) {
        try {
            transport.onDead(event.toEnvelope());
        } catch (RuntimeException callbackFailure) {
            log.error("onDead failed for outbox event {}", event.getId(), callbackFailure);
        }
    }
}
