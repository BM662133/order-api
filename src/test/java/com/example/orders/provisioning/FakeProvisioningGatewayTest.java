package com.example.orders.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.example.orders.order.domain.SimType;

class FakeProvisioningGatewayTest {

    private final FakeProvisioningGateway gateway = new FakeProvisioningGateway();

    private static LineRequest line(String msisdn) {
        return new LineRequest(UUID.randomUUID(), msisdn, "BASIC_5G", SimType.ESIM);
    }

    @Test
    void remembersActiveLines() {
        LineRequest line = line("447700901234");
        assertThat(gateway.isActive(line)).isFalse();

        gateway.activate(line);
        gateway.activate(line);
        assertThat(gateway.isActive(line)).isTrue();

        gateway.deactivate(line);
        assertThat(gateway.isActive(line)).isFalse();
    }

    @Test
    void deactivatingANeverActivatedLineIsANoOp() {
        LineRequest line = line("447700901234");

        gateway.deactivate(line);

        assertThat(gateway.isActive(line)).isFalse();
    }

    @Test
    void rejectedActivationsLeaveNoLine() {
        LineRequest line = line("447700900000");

        assertThatThrownBy(() -> gateway.activate(line)).isInstanceOf(GatewayRejectedException.class);
        assertThat(gateway.isActive(line)).isFalse();
    }

    @Test
    void theUnavailableSuffixFailsEveryCall() {
        LineRequest line = line("447700900001");

        assertThatThrownBy(() -> gateway.activate(line)).isInstanceOf(GatewayUnavailableException.class);
        assertThatThrownBy(() -> gateway.deactivate(line)).isInstanceOf(GatewayUnavailableException.class);
        assertThatThrownBy(() -> gateway.isActive(line)).isInstanceOf(GatewayUnavailableException.class);
    }
}
