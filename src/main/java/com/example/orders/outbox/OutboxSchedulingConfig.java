package com.example.orders.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Polls {@link OutboxRelay} on a single scheduler thread. {@code orders.outbox.scheduling-enabled=false}
 * removes this bean (and scheduling altogether) so that tests can call {@code relayDue()} themselves.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "orders.outbox.scheduling-enabled", havingValue = "true", matchIfMissing = true)
class OutboxSchedulingConfig {

    private final OutboxRelay relay;

    OutboxSchedulingConfig(OutboxRelay relay) {
        this.relay = relay;
    }

    /** Fixed delay, not rate: the next poll starts only after this batch has finished. */
    @Scheduled(fixedDelayString = "${orders.outbox.poll-interval:500ms}")
    void relayDueEvents() {
        relay.relayDue();
    }
}
