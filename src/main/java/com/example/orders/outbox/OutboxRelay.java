package com.example.orders.outbox;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

/**
 * Delivers due outbox events through the {@link OutboxTransport}, at least once, retrying failures with
 * backoff. Deliberately not transactional: delivery may do network I/O (handlers, a broker).
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
     * Delivers one batch of due events, oldest first and one at a time (so events with the same key keep
     * their order). A failure is recorded on its event and does not stop the batch.
     *
     * @return how many events were attempted
     */
    public int relayDue() {
        List<OutboxEvent> due = outboxRepository.findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                OutboxStatus.PENDING, clock.instant(), Limit.of(properties.batchSize()));
        due.forEach(this::deliver);
        return due.size();
    }

    private void deliver(OutboxEvent event) {
        EventEnvelope envelope = event.toEnvelope();
        try {
            transport.deliver(envelope);
        } catch (UnknownEventTypeException ex) {
            event.recordFailure(ex.getMessage(), Duration.ZERO, clock);
            event.markDead();
            outboxRepository.save(event);
            log.error("Outbox event {} has unknown type {}; marked DEAD", event.getId(), event.getEventType());
            return;
        } catch (RuntimeException ex) {
            recordFailure(event, envelope, ex);
            return;
        }
        event.markProcessed(clock);
        outboxRepository.save(event);
    }

    private void recordFailure(OutboxEvent event, EventEnvelope envelope, RuntimeException ex) {
        event.recordFailure(ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                properties.backoffAfter(event.getAttempts() + 1), clock);
        if (event.getAttempts() < properties.maxAttempts()) {
            outboxRepository.save(event);
            log.warn("Outbox event {} ({}) failed, attempt {}/{}; retrying at {}: {}", event.getId(),
                    event.getEventType(), event.getAttempts(), properties.maxAttempts(), event.getNextAttemptAt(),
                    ex.toString());
            return;
        }
        event.markDead();
        outboxRepository.save(event);
        log.error("Outbox event {} ({}) failed {} times; marked DEAD", event.getId(), event.getEventType(),
                event.getAttempts(), ex);
        try {
            transport.onDead(envelope);
        } catch (RuntimeException callbackFailure) {
            log.error("onDead failed for outbox event {}", event.getId(), callbackFailure);
        }
    }
}
