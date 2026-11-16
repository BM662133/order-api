package com.example.orders.outbox.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import com.example.orders.outbox.OutboxEventHandlers;
import com.example.orders.outbox.OutboxProperties;
import com.example.orders.outbox.UnknownEventTypeException;

/**
 * Kafka wiring, active only with {@code orders.outbox.transport=kafka}, so the default mode never
 * touches a broker. Spring Boot applies the {@link DefaultErrorHandler} bean to the listener container.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "orders.outbox.transport", havingValue = "kafka")
class KafkaOutboxConfig {

    @Bean
    NewTopic outboxTopic(@Value("${orders.outbox.kafka.partitions:3}") int partitions) {
        return TopicBuilder.name(OutboxKafkaCodec.TOPIC).partitions(partitions).replicas(1).build();
    }

    /** Same partition count: a dead letter goes to the partition number it came from. */
    @Bean
    NewTopic outboxDeadLetterTopic(@Value("${orders.outbox.kafka.partitions:3}") int partitions) {
        return TopicBuilder.name(OutboxKafkaCodec.DEAD_LETTER_TOPIC).partitions(partitions).replicas(1).build();
    }

    /**
     * Blocking retries in place, with the same backoff and attempt limit as the outbox relay, then the
     * dead-letter topic. A failing record holds up its partition while it retries; that keeps per-key
     * order, and during a gateway outage the other records would fail too.
     */
    @Bean
    DefaultErrorHandler outboxKafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate,
            OutboxEventHandlers handlers, OutboxProperties properties) {
        DeadLetterPublishingRecoverer deadLetters = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, ex) -> new TopicPartition(OutboxKafkaCodec.DEAD_LETTER_TOPIC, record.partition()));
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(properties.maxAttempts() - 1);
        // ExponentialBackOff needs a positive interval; the tests configure a zero backoff.
        backOff.setInitialInterval(Math.max(1, properties.initialBackoff().toMillis()));
        backOff.setMultiplier(2);
        backOff.setMaxInterval(Math.max(1, properties.maxBackoff().toMillis()));
        DefaultErrorHandler errorHandler =
                new DefaultErrorHandler(new OutboxDeadLetterRecoverer(handlers, deadLetters), backOff);
        errorHandler.addNotRetryableExceptions(InvalidOutboxRecordException.class, UnknownEventTypeException.class);
        return errorHandler;
    }
}
