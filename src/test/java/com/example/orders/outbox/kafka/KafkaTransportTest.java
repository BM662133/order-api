package com.example.orders.outbox.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import com.example.orders.outbox.DeliveryResult;
import com.example.orders.outbox.EventEnvelope;

class KafkaTransportTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
    private final KafkaTransport transport = new KafkaTransport(template, Duration.ofMillis(200));

    private static EventEnvelope event() {
        return new EventEnvelope(UUID.randomUUID(), "OrderCreated", UUID.randomUUID(), "partner-a",
                "partner-a:" + UUID.randomUUID(), "{}");
    }

    private static ProducerRecord<String, String> recordOf(EventEnvelope event) {
        return argThat(r -> r != null && r.key().equals(event.messageKey()));
    }

    private static CompletableFuture<SendResult<String, String>> acked() {
        return CompletableFuture.completedFuture(null);
    }

    private static CompletableFuture<SendResult<String, String>> failing(Throwable cause) {
        return CompletableFuture.failedFuture(cause);
    }

    // ---------- deliver (single) ----------

    @Test
    @SuppressWarnings("unchecked")
    void deliverSendsTheEncodedRecordAndReturnsOnceAcknowledged() {
        EventEnvelope event = event();
        when(template.send(any(ProducerRecord.class))).thenReturn(acked());

        transport.deliver(event);

        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template).send(sent.capture());
        assertThat(sent.getValue().topic()).isEqualTo(OutboxKafkaCodec.TOPIC);
        assertThat(sent.getValue().key()).isEqualTo(event.messageKey());
    }

    @Test
    void deliverThrowsAPublishFailureForTheRelayToRetry() {
        EventEnvelope event = event();
        CompletableFuture<SendResult<String, String>> pending = new CompletableFuture<>();
        when(template.send(recordOf(event))).thenReturn(pending);
        pending.completeExceptionally(new KafkaException("broker down"));

        assertThatThrownBy(() -> transport.deliver(event))
                .isInstanceOf(OutboxPublishException.class)
                .hasRootCauseInstanceOf(KafkaException.class);
    }

    // ---------- deliverAll (batch) ----------

    @Test
    void everyRecordIsSentBeforeAnyAckIsAwaited() {
        EventEnvelope a = event();
        EventEnvelope b = event();
        CompletableFuture<SendResult<String, String>> ackA = new CompletableFuture<>();
        CompletableFuture<SendResult<String, String>> ackB = new CompletableFuture<>();
        when(template.send(recordOf(a))).thenReturn(ackA);
        when(template.send(recordOf(b))).thenAnswer(invocation -> {
            assertThat(ackA).as("a's ack must not have been awaited yet").isNotDone();
            ackA.complete(null);
            ackB.complete(null);
            return ackB;
        });

        List<DeliveryResult> results = transport.deliverAll(List.of(a, b));

        assertThat(results).allSatisfy(r -> assertThat(r).isInstanceOf(DeliveryResult.Delivered.class));
    }

    @Test
    void oneFailedAckFailsOnlyItsOwnEvent() {
        EventEnvelope a = event();
        EventEnvelope b = event();
        EventEnvelope c = event();
        CompletableFuture<SendResult<String, String>> ackB = new CompletableFuture<>();
        when(template.send(recordOf(a))).thenReturn(acked());
        when(template.send(recordOf(b))).thenReturn(ackB);
        when(template.send(recordOf(c))).thenAnswer(invocation -> {
            ackB.completeExceptionally(new KafkaException("not leader"));
            return acked();
        });

        List<DeliveryResult> results = transport.deliverAll(List.of(a, b, c));

        assertThat(results.get(0)).isInstanceOf(DeliveryResult.Delivered.class);
        assertThat(results.get(1)).isInstanceOfSatisfying(DeliveryResult.Failed.class, f -> {
            assertThat(f.error()).isInstanceOf(OutboxPublishException.class);
            assertThat(f.error()).hasRootCauseInstanceOf(KafkaException.class);
        });
        assertThat(results.get(2)).isInstanceOf(DeliveryResult.Delivered.class);
    }

    @Test
    void aSendThatThrowsStopsTheBatchAndTheRestAreNotAttempted() {
        EventEnvelope a = event();
        EventEnvelope b = event();
        EventEnvelope c = event();
        when(template.send(recordOf(a))).thenReturn(acked());
        when(template.send(recordOf(b))).thenThrow(new KafkaException("Send failed"));

        List<DeliveryResult> results = transport.deliverAll(List.of(a, b, c));

        assertThat(results.get(0)).isInstanceOf(DeliveryResult.Delivered.class);
        assertThat(results.get(1)).isInstanceOf(DeliveryResult.Failed.class);
        assertThat(results.get(2)).isInstanceOf(DeliveryResult.NotAttempted.class);
        verify(template, never()).send(recordOf(c));
    }

    @Test
    void aSendThatFailsImmediatelyAlsoStopsTheBatch() {
        EventEnvelope a = event();
        EventEnvelope b = event();
        when(template.send(recordOf(a))).thenReturn(failing(new org.apache.kafka.common.errors.TimeoutException(
                "Topic orders.events not present in metadata after 500 ms.")));

        List<DeliveryResult> results = transport.deliverAll(List.of(a, b));

        assertThat(results.get(0)).isInstanceOfSatisfying(DeliveryResult.Failed.class,
                f -> assertThat(f.error()).hasRootCauseInstanceOf(org.apache.kafka.common.errors.TimeoutException.class));
        assertThat(results.get(1)).isInstanceOf(DeliveryResult.NotAttempted.class);
        verify(template, times(1)).send(any(ProducerRecord.class));
    }

    @Test
    void acksStillPendingAtTheDeadlineFailWhileTheOthersSucceed() {
        EventEnvelope acknowledged = event();
        EventEnvelope hanging = event();
        when(template.send(recordOf(acknowledged))).thenReturn(acked());
        when(template.send(recordOf(hanging))).thenReturn(new CompletableFuture<>());

        long start = System.nanoTime();
        List<DeliveryResult> results = transport.deliverAll(List.of(hanging, acknowledged));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(results.get(0)).isInstanceOfSatisfying(DeliveryResult.Failed.class,
                f -> assertThat(f.error()).hasCauseInstanceOf(TimeoutException.class));
        assertThat(results.get(1)).isInstanceOf(DeliveryResult.Delivered.class);
        assertThat(elapsedMs).as("one overall deadline (200 ms), not one per record").isLessThan(1_000);
    }

    @Test
    void anEmptyBatchSendsNothing() {
        assertThat(transport.deliverAll(List.of())).isEmpty();
        verifyNoInteractions(template);
    }

    @Test
    void givingUpOnPublishingLeavesTheOrderAlone() {
        transport.onDead(event());

        verifyNoInteractions(template);
    }
}
