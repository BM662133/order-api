package com.example.orders.outbox.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;

import com.example.orders.outbox.EventEnvelope;

class OutboxKafkaCodecTest {

    private final EventEnvelope event = new EventEnvelope(UUID.randomUUID(), "OrderCreated", UUID.randomUUID(),
            "partner-a", "partner-a:447700900123", "{\"orderId\":\"x\"}");

    /** What a consumer would receive for the producer record. */
    private static ConsumerRecord<String, String> received(ProducerRecord<String, String> sent) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(sent.topic(), 0, 0L, sent.key(), sent.value());
        sent.headers().forEach(header -> record.headers().add(header));
        return record;
    }

    @Test
    void theRecordCarriesKeyPayloadAndMetadataHeaders() {
        ProducerRecord<String, String> record = OutboxKafkaCodec.toRecord(event);

        assertThat(record.topic()).isEqualTo("orders.events");
        assertThat(record.key()).isEqualTo("partner-a:447700900123");
        assertThat(record.value()).isEqualTo("{\"orderId\":\"x\"}");
        assertThat(new String(record.headers().lastHeader("eventType").value(), StandardCharsets.UTF_8))
                .isEqualTo("OrderCreated");
    }

    @Test
    void encodingThenDecodingGivesTheSameEnvelope() {
        assertThat(OutboxKafkaCodec.toEnvelope(received(OutboxKafkaCodec.toRecord(event)))).isEqualTo(event);
    }

    @Test
    void aMissingHeaderIsInvalid() {
        ProducerRecord<String, String> sent = OutboxKafkaCodec.toRecord(event);
        sent.headers().remove(OutboxKafkaCodec.EVENT_TYPE);

        assertThatThrownBy(() -> OutboxKafkaCodec.toEnvelope(received(sent)))
                .isInstanceOf(InvalidOutboxRecordException.class)
                .hasMessage("Missing header eventType");
    }

    @Test
    void aMalformedIdHeaderIsInvalid() {
        ProducerRecord<String, String> sent = OutboxKafkaCodec.toRecord(event);
        sent.headers().remove(OutboxKafkaCodec.EVENT_ID);
        sent.headers().add(OutboxKafkaCodec.EVENT_ID, "nope".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> OutboxKafkaCodec.toEnvelope(received(sent)))
                .isInstanceOf(InvalidOutboxRecordException.class);
    }

    @Test
    void aRecordWithoutAValueIsInvalid() {
        assertThatThrownBy(() -> OutboxKafkaCodec.toEnvelope(new ConsumerRecord<>("orders.events", 0, 0L, "k", null)))
                .isInstanceOf(InvalidOutboxRecordException.class);
    }
}
