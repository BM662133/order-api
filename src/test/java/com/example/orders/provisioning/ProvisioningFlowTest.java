package com.example.orders.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.orders.order.app.IdempotencyKey;
import com.example.orders.order.domain.OrderStatus;
import com.example.orders.outbox.OutboxProperties;
import com.example.orders.outbox.OutboxRelay;
import com.example.orders.support.OutboxTestSupport;
import com.example.orders.support.StatusHistory;
import com.example.orders.tenant.PartnerAuthenticationInterceptor;
import com.jayway.jsonpath.JsonPath;

/**
 * Create -> outbox -> relay -> worker -> gateway against the real database. Scheduling is off in tests,
 * so each test drives the relay itself with {@link OutboxRelay#relayDue()}; the real gateway fake is
 * wrapped in a spy to count and intercept calls.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProvisioningFlowTest {

    private static final String PARTNER = PartnerAuthenticationInterceptor.PARTNER_HEADER;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private OutboxProperties outboxProperties;

    @MockitoSpyBean
    private FakeProvisioningGateway gateway;

    @BeforeEach
    void drainPendingEvents() {
        OutboxTestSupport.drain(relay);
        clearInvocations(gateway);
    }

    private static String msisdnEndingIn(String lastFour) {
        return OutboxTestSupport.uniqueMsisdn(lastFour);
    }

    private List<String> timeline(String id) {
        return StatusHistory.timeline(jdbc, id);
    }

    private String createOrder(String partner, String msisdn, String planCode) throws Exception {
        String body = mockMvc.perform(post("/partners/{partnerId}/orders", partner)
                        .header(PARTNER, partner)
                        .header(IdempotencyKey.HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"msisdn": "%s", "planCode": "%s", "simType": "ESIM"}""".formatted(msisdn, planCode)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private String createOrder(String msisdn) throws Exception {
        return createOrder("partner-a", msisdn, "BASIC_5G");
    }

    private ResultActions cancel(String id) throws Exception {
        return mockMvc.perform(post("/orders/{id}/cancel", id).header(PARTNER, "partner-a"));
    }

    private Map<String, Object> order(String id) {
        return jdbc.queryForMap("SELECT status, failure_reason, cancel_requested_at, version FROM orders WHERE id = ?",
                UUID.fromString(id));
    }

    private Map<String, Object> event(String id) {
        return jdbc.queryForMap("SELECT status, attempts, last_error FROM outbox_events WHERE aggregate_id = ?",
                UUID.fromString(id));
    }

    private void setStatus(String id, OrderStatus status) {
        jdbc.update("UPDATE orders SET status = ? WHERE id = ?", status.name(), UUID.fromString(id));
    }

    private static LineRequest forOrder(String id) {
        UUID orderId = UUID.fromString(id);
        return argThat(line -> line != null && line.orderId().equals(orderId));
    }

    @Test
    void anOrderIsProvisionedToActiveWithOneActivation() throws Exception {
        String id = createOrder(msisdnEndingIn("1234"));

        relay.relayDue();

        assertThat(order(id)).containsEntry("status", "ACTIVE").containsEntry("failure_reason", null);
        assertThat(event(id)).containsEntry("status", "PROCESSED").containsEntry("attempts", 0);
        verify(gateway, times(1)).activate(forOrder(id));
        verify(gateway, never()).deactivate(forOrder(id));
        mockMvc.perform(get("/orders/{id}", id).header(PARTNER, "partner-a"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.failureReason").isEmpty());
        assertThat(timeline(id)).containsExactly(
                "null->CREATED by CLIENT",
                "CREATED->VALIDATED by SYSTEM",
                "VALIDATED->PROVISIONING by SYSTEM",
                "PROVISIONING->ACTIVE by SYSTEM");
    }

    @Test
    void aDuplicateDeliveryIsANoOp() throws Exception {
        String id = createOrder(msisdnEndingIn("1234"));
        relay.relayDue();
        Object version = order(id).get("version");

        jdbc.update("UPDATE outbox_events SET status = 'PENDING', processed_at = NULL WHERE aggregate_id = ?",
                UUID.fromString(id));
        relay.relayDue();

        verify(gateway, times(1)).activate(forOrder(id));
        assertThat(order(id)).containsEntry("status", "ACTIVE").containsEntry("version", version);
        assertThat(event(id)).containsEntry("status", "PROCESSED");
        assertThat(timeline(id)).as("no new history for a no-op delivery").hasSize(4);
    }

    @Test
    void aRedeliveryAfterACrashMidProvisioningResumes() throws Exception {
        String id = createOrder(msisdnEndingIn("1234"));
        setStatus(id, OrderStatus.PROVISIONING);

        relay.relayDue();

        assertThat(order(id)).containsEntry("status", "ACTIVE");
        verify(gateway, times(1)).activate(forOrder(id));
    }

    @Test
    void aPlanDeactivatedAfterOrderingFailsValidation() throws Exception {
        String plan = "TMP_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        jdbc.update("INSERT INTO plans (code, name, active) VALUES (?, 'Temporary', TRUE)", plan);
        String id = createOrder("partner-a", msisdnEndingIn("1234"), plan);
        jdbc.update("UPDATE plans SET active = FALSE WHERE code = ?", plan);

        relay.relayDue();

        assertThat(order(id)).containsEntry("status", "FAILED").containsEntry("failure_reason", "PLAN_INACTIVE");
        assertThat(timeline(id)).containsExactly("null->CREATED by CLIENT", "CREATED->FAILED PLAN_INACTIVE by SYSTEM");
        verify(gateway, never()).activate(forOrder(id));
        mockMvc.perform(get("/orders/{id}", id).header(PARTNER, "partner-a"))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("PLAN_INACTIVE"));
    }

    @Test
    void ofTwoOrdersForOneMsisdnOnlyOneIsProvisioned() throws Exception {
        String msisdn = msisdnEndingIn("1234");
        String first = createOrder(msisdn);
        String second = createOrder(msisdn);

        relay.relayDue();

        assertThat(List.of(order(first), order(second)))
                .extracting(row -> row.get("status") + "/" + row.get("failure_reason"))
                .containsExactlyInAnyOrder("ACTIVE/null", "FAILED/MSISDN_IN_USE");
    }

    @Test
    void theSameMsisdnUnderAnotherPartnerIsIndependent() throws Exception {
        String msisdn = msisdnEndingIn("1234");
        String forA = createOrder("partner-a", msisdn, "BASIC_5G");
        String forB = createOrder("partner-b", msisdn, "BASIC_5G");

        relay.relayDue();

        assertThat(order(forA)).containsEntry("status", "ACTIVE");
        assertThat(order(forB)).containsEntry("status", "ACTIVE");
    }

    @Test
    void aGatewayRejectionFailsTheOrder() throws Exception {
        String id = createOrder(msisdnEndingIn(FakeProvisioningGateway.REJECTED_SUFFIX));

        relay.relayDue();

        assertThat(order(id)).containsEntry("status", "FAILED").containsEntry("failure_reason", "GATEWAY_REJECTED");
        assertThat(event(id)).containsEntry("status", "PROCESSED");
        assertThat(timeline(id)).last().isEqualTo("PROVISIONING->FAILED GATEWAY_REJECTED by SYSTEM");
    }

    @Test
    void anUnavailableGatewayIsRetriedThenTheOrderFails() throws Exception {
        String id = createOrder(msisdnEndingIn(FakeProvisioningGateway.UNAVAILABLE_SUFFIX));

        relay.relayDue();

        assertThat(order(id)).containsEntry("status", "PROVISIONING");
        assertThat(event(id)).containsEntry("status", "PENDING").containsEntry("attempts", 1);
        assertThat((String) event(id).get("last_error")).startsWith("GatewayUnavailableException");

        for (int attempt = 2; attempt <= outboxProperties.maxAttempts(); attempt++) {
            relay.relayDue();
        }

        assertThat(event(id)).containsEntry("status", "DEAD").containsEntry("attempts", outboxProperties.maxAttempts());
        assertThat(order(id)).containsEntry("status", "FAILED")
                .containsEntry("failure_reason", "PROVISIONING_UNAVAILABLE");
        verify(gateway, times(outboxProperties.maxAttempts())).activate(forOrder(id));
    }

    @Test
    void anOrderCancelledBeforeProvisioningNeverReachesTheGateway() throws Exception {
        String id = createOrder(msisdnEndingIn("1234"));
        cancel(id).andExpect(status().isOk());

        relay.relayDue();

        assertThat(order(id)).containsEntry("status", "CANCELLED");
        assertThat(event(id)).containsEntry("status", "PROCESSED");
        verify(gateway, never()).activate(forOrder(id));
        assertThat(timeline(id)).containsExactly("null->CREATED by CLIENT", "CREATED->CANCELLED by CLIENT");
    }

    /** The cancel arrives while activate() is in flight, the race compensation exists for. */
    @Test
    void aCancelDuringActivationIsCompensated() throws Exception {
        String id = createOrder(msisdnEndingIn("1234"));
        AtomicInteger cancelStatus = new AtomicInteger();
        doAnswer(invocation -> {
            cancelStatus.set(cancel(id).andReturn().getResponse().getStatus());
            return invocation.callRealMethod();
        }).when(gateway).activate(forOrder(id));

        relay.relayDue();

        assertThat(cancelStatus.get()).isEqualTo(202);
        assertThat(order(id)).containsEntry("status", "CANCELLED");
        assertThat(order(id).get("cancel_requested_at")).isNotNull();
        verify(gateway, times(1)).activate(forOrder(id));
        verify(gateway, times(1)).deactivate(forOrder(id));
        cancel(id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(timeline(id)).as("the cancel request is not a status change").containsExactly(
                "null->CREATED by CLIENT",
                "CREATED->VALIDATED by SYSTEM",
                "VALIDATED->PROVISIONING by SYSTEM",
                "PROVISIONING->CANCELLED by SYSTEM");
    }

    @Test
    void aCompensationThatKeepsFailingEndsFailed() throws Exception {
        String id = createOrder(msisdnEndingIn(FakeProvisioningGateway.UNAVAILABLE_SUFFIX));
        setStatus(id, OrderStatus.PROVISIONING);
        cancel(id).andExpect(status().isAccepted());

        for (int attempt = 1; attempt <= outboxProperties.maxAttempts(); attempt++) {
            relay.relayDue();
        }

        assertThat(order(id)).containsEntry("status", "FAILED").containsEntry("failure_reason", "COMPENSATION_FAILED");
        verify(gateway, times(outboxProperties.maxAttempts())).deactivate(forOrder(id));
        verify(gateway, never()).activate(forOrder(id));
    }
}
