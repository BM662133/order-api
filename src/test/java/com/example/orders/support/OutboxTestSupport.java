package com.example.orders.support;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.orders.outbox.OutboxRelay;

/** Helpers for tests that drive provisioning through {@link OutboxRelay#relayDue()} by hand. */
public final class OutboxTestSupport {

    private static final AtomicInteger MSISDN_SEQ = new AtomicInteger(ThreadLocalRandom.current().nextInt(500_000));

    private OutboxTestSupport() {
    }

    /**
     * Every test context shares one database: settles events other test classes left behind, so the next
     * {@code relayDue()} sees only this test's events. Terminates because failing events die after
     * max-attempts rounds.
     */
    public static void drain(OutboxRelay relay) {
        while (relay.relayDue() > 0) {
            // keep going until nothing is due
        }
    }

    /**
     * An MSISDN no other test uses (most share +447700900123, which would trip MSISDN_IN_USE), ending in
     * {@code lastFour} to pick the fake gateway's behaviour.
     */
    public static String uniqueMsisdn(String lastFour) {
        return String.format("+4475%06d%s", MSISDN_SEQ.incrementAndGet(), lastFour);
    }
}
