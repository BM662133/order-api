package com.example.orders.outbox;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** InProcessTransport over OutboxEventHandlers: dispatch by type, and giving up reaches the handler. */
class InProcessTransportTest {

    private final OutboxEventHandler handler = mock(OutboxEventHandler.class);
    private final InProcessTransport transport;

    InProcessTransportTest() {
        when(handler.eventType()).thenReturn("TestEvent");
        transport = new InProcessTransport(new OutboxEventHandlers(List.of(handler)));
    }

    private static EventEnvelope envelope(String type) {
        return new EventEnvelope(UUID.randomUUID(), type, UUID.randomUUID(), "partner-a", "key-1", "{}");
    }

    @Test
    void deliverCallsTheHandlerForTheType() {
        EventEnvelope event = envelope("TestEvent");

        transport.deliver(event);

        verify(handler).handle(event);
    }

    @Test
    void anUnknownTypeIsRejected() {
        assertThatThrownBy(() -> transport.deliver(envelope("Unknown")))
                .isInstanceOf(UnknownEventTypeException.class)
                .hasMessage("No handler for event type Unknown");
        verify(handler, never()).handle(any());
    }

    @Test
    void onDeadTellsTheHandler() {
        EventEnvelope event = envelope("TestEvent");

        transport.onDead(event);

        verify(handler).onRetriesExhausted(event);
    }

    @Test
    void onDeadForAnUnknownTypeIsANoOp() {
        assertThatCode(() -> transport.onDead(envelope("Unknown"))).doesNotThrowAnyException();
    }

    @Test
    void twoHandlersForOneTypeFailAtStartup() {
        OutboxEventHandler duplicate = mock(OutboxEventHandler.class);
        when(duplicate.eventType()).thenReturn("TestEvent");

        assertThatThrownBy(() -> new OutboxEventHandlers(List.of(handler, duplicate)))
                .isInstanceOf(IllegalStateException.class);
    }
}
