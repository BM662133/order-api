package com.example.orders.order.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class IdempotencyKeyTest {

    @ParameterizedTest
    @ValueSource(strings = {"k", "3f2b8c1e-6a4d-4e0b-9a51-2c7d1f0e9b11", "order:2026-01-01/#42", "~!@$%^&*()_+"})
    void visibleAsciiKeysAreAccepted(String raw) {
        assertThat(new IdempotencyKey(raw).value()).isEqualTo(raw);
    }

    @Test
    void exactlyMaxLengthIsAccepted() {
        String raw = "a".repeat(IdempotencyKey.MAX_LENGTH);
        assertThat(new IdempotencyKey(raw).value()).hasSize(100);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void missingKeyIsRejected(String raw) {
        assertThatThrownBy(() -> new IdempotencyKey(raw))
                .isInstanceOf(InvalidIdempotencyKeyException.class)
                .hasMessage("Missing Idempotency-Key header.");
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "has space", "tab\there", "café", "line\nbreak"})
    void nonVisibleAsciiIsRejected(String raw) {
        assertThatThrownBy(() -> new IdempotencyKey(raw))
                .isInstanceOf(InvalidIdempotencyKeyException.class)
                .hasMessageContaining("visible ASCII");
    }

    @Test
    void overMaxLengthIsRejected() {
        assertThatThrownBy(() -> new IdempotencyKey("a".repeat(IdempotencyKey.MAX_LENGTH + 1)))
                .isInstanceOf(InvalidIdempotencyKeyException.class);
    }
}
