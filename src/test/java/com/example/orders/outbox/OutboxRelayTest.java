package com.example.orders.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Limit;

/** Bookkeeping per delivery result: processed, retried with backoff, dead after max attempts, untouched. */
class OutboxRelayTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);
    private static final int MAX_ATTEMPTS = 3;

    private final OutboxRepository repository = mock(OutboxRepository.class);
    private final OutboxTransport transport = mock(OutboxTransport.class);
    private final OutboxRelay relay = new OutboxRelay(repository, transport,
            new OutboxProperties(50, MAX_ATTEMPTS, Duration.ofSeconds(1), Duration.ofSeconds(60)), CLOCK);

    private static OutboxEvent event() {
        return OutboxEvent.pending(UUID.randomUUID(), "partner-a", "TestEvent", "key-" + UUID.randomUUID(), "{}", CLOCK);
    }

    private void due(OutboxEvent... events) {
        when(repository.findDue(OutboxStatus.PENDING, T0, Limit.of(50))).thenReturn(List.of(events));
    }

    private void results(DeliveryResult... results) {
        when(transport.deliverAll(anyList())).thenReturn(List.of(results));
    }

    private static DeliveryResult failed(String message) {
        return DeliveryResult.failed(new IllegalStateException(message));
    }

    @SuppressWarnings("unchecked")
    private List<OutboxEvent> saved() {
        ArgumentCaptor<Iterable<OutboxEvent>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(repository).saveAll(captor.capture());
        return (List<OutboxEvent>) captor.getValue();
    }

    @Test
    void theWholeBatchGoesToTheTransportAsEnvelopesInOrder() {
        OutboxEvent first = event();
        OutboxEvent second = event();
        due(first, second);
        results(DeliveryResult.delivered(), DeliveryResult.delivered());

        relay.relayDue();

        verify(transport).deliverAll(List.of(first.toEnvelope(), second.toEnvelope()));
    }

    @Test
    void deliveredEventsAreMarkedProcessedAndSavedTogether() {
        OutboxEvent first = event();
        OutboxEvent second = event();
        due(first, second);
        results(DeliveryResult.delivered(), DeliveryResult.delivered());

        assertThat(relay.relayDue()).isEqualTo(2);

        assertThat(List.of(first, second)).allSatisfy(e -> {
            assertThat(e.getStatus()).isEqualTo(OutboxStatus.PROCESSED);
            assertThat(e.getProcessedAt()).isEqualTo(T0);
            assertThat(e.getAttempts()).isZero();
        });
        assertThat(saved()).containsExactly(first, second);
    }

    @Test
    void eachResultAppliesToItsOwnEvent() {
        OutboxEvent delivered = event();
        OutboxEvent failed = event();
        OutboxEvent skipped = event();
        due(delivered, failed, skipped);
        results(DeliveryResult.delivered(), failed("boom"), DeliveryResult.notAttempted());

        assertThat(relay.relayDue()).as("skipped events are not attempts").isEqualTo(2);

        assertThat(delivered.getStatus()).isEqualTo(OutboxStatus.PROCESSED);
        assertThat(failed.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(failed.getAttempts()).isEqualTo(1);
        assertThat(failed.getNextAttemptAt()).isEqualTo(T0.plusSeconds(1));
        assertThat(failed.getLastError()).isEqualTo("IllegalStateException: boom");
        assertThat(skipped.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(skipped.getAttempts()).isZero();
        assertThat(skipped.getNextAttemptAt()).as("still due").isEqualTo(T0);
        assertThat(skipped.getLastError()).isNull();
        assertThat(saved()).containsExactly(delivered, failed);
    }

    @Test
    void theLastAllowedFailureMarksTheEventDeadAndTellsTheTransportAfterSaving() {
        OutboxEvent event = event();
        due(event);
        results(failed("boom"));

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
        results(failed("boom"));
        doThrow(new IllegalStateException("callback")).when(transport).onDead(any());

        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            relay.relayDue();
        }

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.DEAD);
    }

    @Test
    void anUnknownEventTypeIsMarkedDeadWithoutRetrying() {
        OutboxEvent event = event();
        due(event);
        results(DeliveryResult.failed(new UnknownEventTypeException("TestEvent")));

        relay.relayDue();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.DEAD);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getLastError()).isEqualTo("No handler for event type TestEvent");
        verify(transport, never()).onDead(any());
    }

    @Test
    void nothingDueTouchesNeitherTransportNorDatabase() {
        due();

        assertThat(relay.relayDue()).isZero();

        verifyNoInteractions(transport);
        verify(repository, never()).saveAll(any());
    }

    @Test
    void aTransportReturningTheWrongNumberOfResultsIsAProgrammingError() {
        due(event(), event());
        results(DeliveryResult.delivered());

        assertThatThrownBy(relay::relayDue).isInstanceOf(IllegalStateException.class);
        verify(repository, never()).saveAll(any());
    }
}
