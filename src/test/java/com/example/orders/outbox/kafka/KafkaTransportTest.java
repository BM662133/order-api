package com.example.orders.outbox.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import com.example.orders.outbox.EventEnvelope;

class KafkaTransportTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
    private final KafkaTransport transport = new KafkaTransport(template, Duration.ofMillis(200));

    private final EventEnvelope event = new EventEnvelope(UUID.randomUUID(), "OrderCreated", UUID.randomUUID(),
            "partner-a", "partner-a:447700900123", "{}");

    @SuppressWarnings("unchecked")
    private void sendReturns(CompletableFuture<SendResult<String, String>> future) {
        when(template.send(any(ProducerRecord.class))).thenReturn(future);
    }

    @Test
    void deliverSendsTheEncodedRecordAndReturnsOnceAcknowledged() {
        sendReturns(CompletableFuture.completedFuture(null));

        transport.deliver(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template).send(sent.capture());
        assertThat(sent.getValue().topic()).isEqualTo(OutboxKafkaCodec.TOPIC);
        assertThat(sent.getValue().key()).isEqualTo("partner-a:447700900123");
    }

    @Test
    void aFailedSendIsAPublishFailureForTheRelayToRetry() {
        sendReturns(CompletableFuture.failedFuture(new KafkaException("broker down")));

        assertThatThrownBy(() -> transport.deliver(event))
                .isInstanceOf(OutboxPublishException.class)
                .hasRootCauseInstanceOf(KafkaException.class);
    }

    @Test
    void aSendThatIsNeverAcknowledgedTimesOut() {
        sendReturns(new CompletableFuture<>());

        assertThatThrownBy(() -> transport.deliver(event))
                .isInstanceOf(OutboxPublishException.class)
                .hasCauseInstanceOf(TimeoutException.class);
    }

    @Test
    void givingUpOnPublishingLeavesTheOrderAlone() {
        transport.onDead(event);

        verifyNoInteractions(template);
    }
}
