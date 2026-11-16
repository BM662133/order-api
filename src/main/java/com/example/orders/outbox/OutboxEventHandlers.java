package com.example.orders.outbox;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/** Routes an event to the handler for its type; shared by the in-process transport and the Kafka listener. */
@Component
public class OutboxEventHandlers {

    private final Map<String, OutboxEventHandler> byType;

    public OutboxEventHandlers(List<OutboxEventHandler> handlers) {
        // toMap fails fast on two handlers for one event type.
        this.byType = handlers.stream().collect(Collectors.toMap(OutboxEventHandler::eventType, Function.identity()));
    }

    /** @throws UnknownEventTypeException if no handler is registered for the event's type */
    public void dispatch(EventEnvelope event) {
        OutboxEventHandler handler = byType.get(event.eventType());
        if (handler == null) {
            throw new UnknownEventTypeException(event.eventType());
        }
        handler.handle(event);
    }

    /** Tells the event's handler, if there is one, that it will not be delivered again. */
    public void retriesExhausted(EventEnvelope event) {
        OutboxEventHandler handler = byType.get(event.eventType());
        if (handler != null) {
            handler.onRetriesExhausted(event);
        }
    }
}
