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
    private static final String TYPE = "TestEvent";
    private static final int MAX_ATTEMPTS = 3;

    private final OutboxRepository repository = mock(OutboxRepository.class);
    private final OutboxEventHandler handler = mock(OutboxEventHandler.class);
    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        when(handler.eventType()).thenReturn(TYPE);
        when(repository.save(any())).then(returnsFirstArg());
        relay = new OutboxRelay(repository, List.of(handler),
                new OutboxProperties(50, MAX_ATTEMPTS, Duration.ofSeconds(1), Duration.ofSeconds(60)), CLOCK);
    }

    private static OutboxEvent event(String type) {
        return OutboxEvent.pending(UUID.randomUUID(), "partner-a", type, "{}", CLOCK);
    }

    private void due(OutboxEvent... events) {
        when(repository.findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                OutboxStatus.PENDING, T0, Limit.of(50))).thenReturn(List.of(events));
    }

    @Test
    void successfulDeliveryMarksTheEventProcessed() {
        OutboxEvent event = event(TYPE);
        due(event);

        assertThat(relay.relayDue()).isEqualTo(1);

        verify(handler).handle(event);
        verify(repository).save(event);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PROCESSED);
        assertThat(event.getProcessedAt()).isEqualTo(T0);
        assertThat(event.getAttempts()).isZero();
    }

    @Test
    void failureSchedulesARetryWithBackoff() {
        OutboxEvent event = event(TYPE);
        due(event);
        doThrow(new IllegalStateException("boom")).when(handler).handle(event);

        relay.relayDue();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getNextAttemptAt()).isEqualTo(T0.plusSeconds(1));
        assertThat(event.getLastError()).isEqualTo("IllegalStateException: boom");
        verify(repository).save(event);
        verify(handler, never()).onRetriesExhausted(any());
    }

    @Test
    void theLastAllowedFailureMarksTheEventDeadAndTellsTheHandler() {
        OutboxEvent event = event(TYPE);
        due(event);
        doThrow(new IllegalStateException("boom")).when(handler).handle(event);

        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            relay.relayDue();
        }

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.DEAD);
        assertThat(event.getAttempts()).isEqualTo(MAX_ATTEMPTS);
        verify(handler, times(1)).onRetriesExhausted(event);
    }

    @Test
    void aFailingRetriesExhaustedCallbackDoesNotEscape() {
        OutboxEvent event = event(TYPE);
        due(event);
        doThrow(new IllegalStateException("boom")).when(handler).handle(event);
        doThrow(new IllegalStateException("callback")).when(handler).onRetriesExhausted(event);

        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            relay.relayDue();
        }

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.DEAD);
    }

    @Test
    void oneFailureDoesNotStopTheBatch() {
        OutboxEvent failing = event(TYPE);
        OutboxEvent next = event(TYPE);
        due(failing, next);
        doThrow(new IllegalStateException("boom")).when(handler).handle(failing);

        assertThat(relay.relayDue()).isEqualTo(2);

        assertThat(failing.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(next.getStatus()).isEqualTo(OutboxStatus.PROCESSED);
    }

    @Test
    void anUnknownEventTypeIsMarkedDeadWithoutRetrying() {
        OutboxEvent event = event("Unknown");
        due(event);

        relay.relayDue();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.DEAD);
        assertThat(event.getLastError()).isEqualTo("No handler for event type Unknown");
        verify(handler, never()).handle(any());
    }
}
