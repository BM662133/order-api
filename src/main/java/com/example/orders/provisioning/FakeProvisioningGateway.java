package com.example.orders.provisioning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stand-in for a real carrier client, with outcomes chosen by the MSISDN so every path can be demoed:
 * ending {@value #REJECTED_SUFFIX} is rejected, ending {@value #UNAVAILABLE_SUFFIX} is always
 * unavailable (activate and deactivate), anything else succeeds.
 */
@Component
public class FakeProvisioningGateway implements ProvisioningGateway {

    public static final String REJECTED_SUFFIX = "0000";
    public static final String UNAVAILABLE_SUFFIX = "0001";

    private static final Logger log = LoggerFactory.getLogger(FakeProvisioningGateway.class);

    @Override
    public void activate(LineRequest line) {
        failIfUnavailable(line);
        if (line.msisdn().endsWith(REJECTED_SUFFIX)) {
            throw new GatewayRejectedException("Activation rejected for order " + line.orderId());
        }
        log.info("Activated line for order {}", line.orderId());
    }

    @Override
    public void deactivate(LineRequest line) {
        failIfUnavailable(line);
        log.info("Deactivated line for order {}", line.orderId());
    }

    private static void failIfUnavailable(LineRequest line) {
        if (line.msisdn().endsWith(UNAVAILABLE_SUFFIX)) {
            throw new GatewayUnavailableException("Gateway unavailable for order " + line.orderId());
        }
    }
}
