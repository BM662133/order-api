package com.example.orders.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class OutboxPropertiesTest {

    private final OutboxProperties properties =
            new OutboxProperties(50, 5, Duration.ofSeconds(1), Duration.ofSeconds(60), Duration.ofSeconds(60));

    @ParameterizedTest
    @CsvSource({"1, 1", "2, 2", "3, 4", "6, 32", "7, 60", "100, 60"})
    void backoffDoublesUpToTheCap(int attempts, long expectedSeconds) {
        assertThat(properties.backoffAfter(attempts)).isEqualTo(Duration.ofSeconds(expectedSeconds));
    }

    @Test
    void zeroInitialBackoffMeansImmediateRetry() {
        assertThat(new OutboxProperties(50, 5, Duration.ZERO, Duration.ofSeconds(60), Duration.ofSeconds(60)).backoffAfter(3)).isZero();
    }

    @Test
    void attemptsStartAtOne() {
        assertThatIllegalArgumentException().isThrownBy(() -> properties.backoffAfter(0));
    }
}
