package com.example.orders.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;

/** Delivery bookkeeping: processed, retried with backoff, dead after max attempts. */
class OutboxRelayTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);
    private static final int MAX_ATTEMPTS = 3;

    private final OutboxRepository repository = mock(OutboxRepository.class);
    private final OutboxTransport transport = mock(OutboxTransport.class);
    private final OutboxRelay relay = new OutboxRelay(repository, transport,
            new OutboxProperties(50, MAX_ATTEMPTS, Duration.ofSeconds(1), Duration.ofSeconds(60)), CLOCK);

    @BeforeEach
    void setUp() {
        when(repository.save(any())).then(returnsFirstArg());
    }

    private static OutboxEvent event() {
        return OutboxEvent.pending(UUID.randomUUID(), "partner-a", "TestEvent", "key-1", "{}", CLOCK);
    }

    private void due(OutboxEvent... events) {
        when(repository.findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                OutboxStatus.PENDING, T0, Limit.of(50))).thenReturn(List.of(events));
    }

    @Test
    void successfulDeliveryMarksTheEventProcessed() {
        OutboxEvent event = event();
        due(event);

        assertThat(relay.relayDue()).isEqualTo(1);

        verify(transport).deliver(event.toEnvelope());
        verify(repository).save(event);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PROCESSED);
        assertThat(event.getProcessedAt()).isEqualTo(T0);
        assertThat(event.getAttempts()).isZero();
    }

    @Test
    void theEnvelopeCarriesTheRowsFields() {
        OutboxEvent event = event();

        EventEnvelope envelope = event.toEnvelope();

        assertThat(envelope).isEqualTo(new EventEnvelope(event.getId(), "TestEvent", event.getAggregateId(),
                "partner-a", "key-1", "{}"));
    }

    @Test
    void failureSchedulesARetryWithBackoff() {
        OutboxEvent event = event();
        due(event);
        doThrow(new IllegalStateException("boom")).when(transport).deliver(event.toEnvelope());

        relay.relayDue();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getNextAttemptAt()).isEqualTo(T0.plusSeconds(1));
        assertThat(event.getLastError()).isEqualTo("IllegalStateException: boom");
        verify(repository).save(event);
        verify(transport, never()).onDead(any());
    }

    @Test
    void theLastAllowedFailureMarksTheEventDeadAndTellsTheTransport() {
        OutboxEvent event = event();
        due(event);
        doThrow(new IllegalStateException("boom")).when(transport).deliver(event.toEnvelope());

        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            relay.relayDue();
        }

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.DEAD);
        assertThat(event.getAttempts()).isEqualTo(MAX_ATTEMPTS);
        verify(transport, times(1)).onDead(event.toEnvelope());
    }

    @Test
    void aFailingOnDeadCallbackDoesNotEscape() {
        OutboxEvent event = event();
        due(event);
        doThrow(new IllegalStateException("boom")).when(transport).deliver(event.toEnvelope());
        doThrow(new IllegalStateException("callback")).when(transport).onDead(event.toEnvelope());

        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            relay.relayDue();
        }

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.DEAD);
    }

    @Test
    void oneFailureDoesNotStopTheBatch() {
        OutboxEvent failing = event();
        OutboxEvent next = event();
        due(failing, next);
        doThrow(new IllegalStateException("boom")).when(transport).deliver(failing.toEnvelope());

        assertThat(relay.relayDue()).isEqualTo(2);

        assertThat(failing.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(next.getStatus()).isEqualTo(OutboxStatus.PROCESSED);
    }

    @Test
    void anUnknownEventTypeIsMarkedDeadWithoutRetrying() {
        OutboxEvent event = event();
        due(event);
        doThrow(new UnknownEventTypeException("TestEvent")).when(transport).deliver(event.toEnvelope());

        relay.relayDue();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.DEAD);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getLastError()).isEqualTo("No handler for event type TestEvent");
        verify(transport, never()).onDead(any());
    }
}
