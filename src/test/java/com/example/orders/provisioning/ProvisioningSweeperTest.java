package com.example.orders.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.orders.order.app.IdempotencyKey;
import com.example.orders.order.app.OrderEvents;
import com.example.orders.outbox.OutboxRelay;
import com.example.orders.support.OutboxTestSupport;
import com.example.orders.tenant.PartnerAuthenticationInterceptor;
import com.jayway.jsonpath.JsonPath;

/**
 * The sweeper against the real database (same context setup as ProvisioningFlowTest). Stalled and
 * uncertain states are produced with SQL, then {@link ProvisioningSweeper#sweep()} is called by hand.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProvisioningSweeperTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private ProvisioningSweeper sweeper;

    @MockitoSpyBean
    private FakeProvisioningGateway gateway;

    @BeforeEach
    void settlePendingEvents() {
        OutboxTestSupport.drain(relay);
        clearInvocations(gateway);
    }

    private String createOrder(String msisdn) throws Exception {
        String body = mockMvc.perform(post("/partners/{partnerId}/orders", "partner-a")
                        .header(PartnerAuthenticationInterceptor.PARTNER_HEADER, "partner-a")
                        .header(IdempotencyKey.HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"msisdn": "%s", "planCode": "BASIC_5G", "simType": "ESIM"}""".formatted(msisdn)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    /** An order whose OrderCreated event was handled (or lost) without the order finishing, an hour ago. */
    private String stalledOrder(String status) throws Exception {
        String id = createOrder(OutboxTestSupport.uniqueMsisdn("1234"));
        jdbc.update("UPDATE outbox_events SET status = 'DEAD' WHERE aggregate_id = ?", UUID.fromString(id));
        jdbc.update("UPDATE orders SET status = ?, updated_at = ? WHERE id = ?", status,
                Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)), UUID.fromString(id));
        return id;
    }

    private String failedOrder(String lastFour, String reason) throws Exception {
        String id = createOrder(OutboxTestSupport.uniqueMsisdn(lastFour));
        jdbc.update("UPDATE outbox_events SET status = 'PROCESSED' WHERE aggregate_id = ?", UUID.fromString(id));
        jdbc.update("UPDATE orders SET status = 'FAILED', failure_reason = ? WHERE id = ?", reason, UUID.fromString(id));
        return id;
    }

    private List<String> eventTypes(String id) {
        return jdbc.queryForList("SELECT event_type FROM outbox_events WHERE aggregate_id = ? ORDER BY created_at",
                String.class, UUID.fromString(id));
    }

    private String statusOf(String id) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, UUID.fromString(id));
    }

    private Object reconciledAt(String id) {
        return jdbc.queryForObject("SELECT reconciled_at FROM orders WHERE id = ?", Object.class, UUID.fromString(id));
    }

    private static LineRequest forOrder(String id) {
        UUID orderId = UUID.fromString(id);
        return argThat(line -> line != null && line.orderId().equals(orderId));
    }

    // ---------- retrying stalled orders ----------

    @Test
    void aStalledOrderGetsARetryEventAndThenFinishes() throws Exception {
        String id = stalledOrder("PROVISIONING");

        assertThat(sweeper.sweep().retried()).isPositive();
        assertThat(eventTypes(id)).containsExactly(OrderEvents.ORDER_CREATED, OrderEvents.PROVISIONING_RETRY_REQUESTED);

        relay.relayDue();

        assertThat(statusOf(id)).isEqualTo("ACTIVE");
        verify(gateway, times(1)).activate(forOrder(id));
    }

    @Test
    void aRetryIsRequestedOnlyOnceWhileItIsPending() throws Exception {
        String id = stalledOrder("CREATED");

        sweeper.sweep();
        sweeper.sweep();

        assertThat(eventTypes(id)).containsExactly(OrderEvents.ORDER_CREATED, OrderEvents.PROVISIONING_RETRY_REQUESTED);
    }

    @Test
    void aRecentlyUpdatedOrderIsLeftAlone() throws Exception {
        String id = createOrder(OutboxTestSupport.uniqueMsisdn("1234"));
        jdbc.update("UPDATE outbox_events SET status = 'DEAD' WHERE aggregate_id = ?", UUID.fromString(id));

        sweeper.sweep();

        assertThat(eventTypes(id)).containsExactly(OrderEvents.ORDER_CREATED);
    }

    @Test
    void anOldOrderWithAPendingEventIsLeftAlone() throws Exception {
        String id = createOrder(OutboxTestSupport.uniqueMsisdn("1234"));
        jdbc.update("UPDATE orders SET updated_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)), UUID.fromString(id));

        sweeper.sweep();

        assertThat(eventTypes(id)).containsExactly(OrderEvents.ORDER_CREATED);
    }

    @Test
    void finishedOrdersAreNeverRetried() throws Exception {
        String id = stalledOrder("CANCELLED");

        sweeper.sweep();

        assertThat(eventTypes(id)).containsExactly(OrderEvents.ORDER_CREATED);
    }

    // ---------- reconciling uncertain failures ----------

    @Test
    void anActiveLineBehindAnUncertainFailureIsDeactivated() throws Exception {
        String id = failedOrder("1234", "PROVISIONING_UNAVAILABLE");
        doReturn(true).when(gateway).isActive(forOrder(id));

        sweeper.sweep();

        verify(gateway, times(1)).deactivate(forOrder(id));
        assertThat(reconciledAt(id)).isNotNull();
        assertThat(statusOf(id)).as("still FAILED; reconciling is not a status change").isEqualTo("FAILED");
    }

    @Test
    void anInactiveLineIsJustMarkedReconciledOnce() throws Exception {
        String id = failedOrder("1234", "COMPENSATION_FAILED");

        sweeper.sweep();
        sweeper.sweep();

        verify(gateway, times(1)).isActive(forOrder(id));
        verify(gateway, never()).deactivate(forOrder(id));
        assertThat(reconciledAt(id)).isNotNull();
    }

    @Test
    void whileTheGatewayIsDownReconciliationIsRetriedNextSweep() throws Exception {
        String id = failedOrder(FakeProvisioningGateway.UNAVAILABLE_SUFFIX, "PROVISIONING_UNAVAILABLE");

        sweeper.sweep();
        sweeper.sweep();

        verify(gateway, times(2)).isActive(forOrder(id));
        assertThat(reconciledAt(id)).isNull();
    }

    @Test
    void certainFailuresAreNotReconciled() throws Exception {
        String id = failedOrder("1234", "GATEWAY_REJECTED");

        sweeper.sweep();

        verify(gateway, never()).isActive(forOrder(id));
        assertThat(reconciledAt(id)).isNull();
    }
}
