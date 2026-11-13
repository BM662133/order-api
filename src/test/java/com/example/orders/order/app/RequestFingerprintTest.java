package com.example.orders.order.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.example.orders.order.domain.SimType;

class RequestFingerprintTest {

    private static final String BASE = RequestFingerprint.of("+447700900123", "BASIC_5G", SimType.ESIM);

    @Test
    void isA64CharacterLowercaseHexSha256() {
        assertThat(BASE).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void isDeterministic() {
        assertThat(RequestFingerprint.of("+447700900123", "BASIC_5G", SimType.ESIM)).isEqualTo(BASE);
    }

    @Test
    void msisdnWithAndWithoutPlusIsTheSameRequest() {
        assertThat(RequestFingerprint.of("447700900123", "BASIC_5G", SimType.ESIM)).isEqualTo(BASE);
    }

    @Test
    void anyDifferentFieldIsADifferentRequest() {
        assertThat(RequestFingerprint.of("+447700900124", "BASIC_5G", SimType.ESIM)).isNotEqualTo(BASE);
        assertThat(RequestFingerprint.of("+447700900123", "UNLIMITED", SimType.ESIM)).isNotEqualTo(BASE);
        assertThat(RequestFingerprint.of("+447700900123", "BASIC_5G", SimType.PHYSICAL)).isNotEqualTo(BASE);
    }
}
