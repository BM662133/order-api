package com.example.orders.provisioning;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stand-in for a real carrier client, with outcomes chosen by the MSISDN so every path can be demoed:
 * ending {@value #REJECTED_SUFFIX} is rejected, ending {@value #UNAVAILABLE_SUFFIX} is always
 * unavailable (every call), anything else succeeds. Remembers which lines are active, per order id.
 */
@Component
public class FakeProvisioningGateway implements ProvisioningGateway {

    public static final String REJECTED_SUFFIX = "0000";
    public static final String UNAVAILABLE_SUFFIX = "0001";

    private static final Logger log = LoggerFactory.getLogger(FakeProvisioningGateway.class);

    private final Set<UUID> activeLines = ConcurrentHashMap.newKeySet();

    @Override
    public void activate(LineRequest line) {
        failIfUnavailable(line);
        if (line.msisdn().endsWith(REJECTED_SUFFIX)) {
            throw new GatewayRejectedException("Activation rejected for order " + line.orderId());
        }
        activeLines.add(line.orderId());
        log.info("Activated line for order {}", line.orderId());
    }

    @Override
    public void deactivate(LineRequest line) {
        failIfUnavailable(line);
        activeLines.remove(line.orderId());
        log.info("Deactivated line for order {}", line.orderId());
    }

    @Override
    public boolean isActive(LineRequest line) {
        failIfUnavailable(line);
        return activeLines.contains(line.orderId());
    }

    private static void failIfUnavailable(LineRequest line) {
        if (line.msisdn().endsWith(UNAVAILABLE_SUFFIX)) {
            throw new GatewayUnavailableException("Gateway unavailable for order " + line.orderId());
        }
    }
}
