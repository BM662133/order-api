package com.example.orders.outbox.kafka;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

import com.example.orders.outbox.EventEnvelope;

/**
 * The wire format of outbox events on Kafka: key = the envelope's message key, value = the JSON
 * payload, and the routing metadata in headers.
 */
public final class OutboxKafkaCodec {

    public static final String TOPIC = "orders.events";
    public static final String DEAD_LETTER_TOPIC = TOPIC + ".DLT";

    public static final String EVENT_ID = "eventId";
    public static final String EVENT_TYPE = "eventType";
    public static final String AGGREGATE_ID = "aggregateId";
    public static final String PARTNER_ID = "partnerId";

    private OutboxKafkaCodec() {
    }

    public static ProducerRecord<String, String> toRecord(EventEnvelope event) {
        ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, event.messageKey(), event.payload());
        Headers headers = record.headers();
        headers.add(EVENT_ID, bytes(event.eventId().toString()));
        headers.add(EVENT_TYPE, bytes(event.eventType()));
        headers.add(AGGREGATE_ID, bytes(event.aggregateId().toString()));
        headers.add(PARTNER_ID, bytes(event.partnerId()));
        return record;
    }

    /** @throws InvalidOutboxRecordException if a header, the key or the value is missing or malformed */
    public static EventEnvelope toEnvelope(ConsumerRecord<String, String> record) {
        if (record.key() == null || record.value() == null) {
            throw new InvalidOutboxRecordException("Record has no key or no value");
        }
        try {
            return new EventEnvelope(
                    UUID.fromString(header(record, EVENT_ID)),
                    header(record, EVENT_TYPE),
                    UUID.fromString(header(record, AGGREGATE_ID)),
                    header(record, PARTNER_ID),
                    record.key(),
                    record.value());
        } catch (IllegalArgumentException ex) {
            throw new InvalidOutboxRecordException("Malformed id header: " + ex.getMessage());
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null || header.value() == null) {
            throw new InvalidOutboxRecordException("Missing header " + name);
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
