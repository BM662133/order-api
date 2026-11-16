package com.example.orders.order.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.example.orders.common.error.ProblemTypes;
import com.example.orders.order.app.IdempotencyKey;
import com.example.orders.order.domain.OrderStatus;
import com.example.orders.tenant.PartnerAuthenticationInterceptor;
import com.jayway.jsonpath.JsonPath;

/**
 * Statuses are set directly with SQL, so each case is deterministic. The provisioning worker doesn't
 * interfere: tests run with outbox scheduling off (src/test/resources/config/application.properties).
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderCancelApiTest {

    private static final String PARTNER = PartnerAuthenticationInterceptor.PARTNER_HEADER;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private String createOrder(String partner) throws Exception {
        MvcResult result = mockMvc.perform(post("/partners/{partnerId}/orders", partner)
                        .header(PARTNER, partner)
                        .header(IdempotencyKey.HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"msisdn": "+447700900123", "planCode": "BASIC_5G", "simType": "ESIM"}"""))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private void setStatus(String id, OrderStatus status) {
        jdbc.update("UPDATE orders SET status = ? WHERE id = ?", status.name(), UUID.fromString(id));
    }

    private String statusOf(String id) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, UUID.fromString(id));
    }

    private long versionOf(String id) {
        return jdbc.queryForObject("SELECT version FROM orders WHERE id = ?", Long.class, UUID.fromString(id));
    }

    private ResultActions cancel(String partner, String id) throws Exception {
        return mockMvc.perform(post("/orders/{id}/cancel", id).header(PARTNER, partner));
    }

    @ParameterizedTest
    @EnumSource(names = {"CREATED", "VALIDATED"})
    void cancelFromAnEarlyStateReturns200WithTheCancelledOrder(OrderStatus from) throws Exception {
        String id = createOrder("partner-a");
        setStatus(id, from);

        cancel("partner-a", id)
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelRequestedAt").isEmpty());

        assertThat(statusOf(id)).isEqualTo("CANCELLED");
        assertThat(versionOf(id)).isEqualTo(1);
        mockMvc.perform(get("/orders/{id}", id).header(PARTNER, "partner-a"))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void repeatCancelIsA200NoOpWithAnIdenticalBody() throws Exception {
        String id = createOrder("partner-a");
        String first = cancel("partner-a", id).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        cancel("partner-a", id)
                .andExpect(status().isOk())
                .andExpect(content().json(first, true));

        assertThat(versionOf(id)).as("the repeat must not write").isEqualTo(1);
    }

    @Test
    void cancelWhileProvisioningIs202AndOnlyRecordsTheRequest() throws Exception {
        String id = createOrder("partner-a");
        setStatus(id, OrderStatus.PROVISIONING);

        cancel("partner-a", id)
                .andExpect(status().isAccepted())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("PROVISIONING"))
                .andExpect(jsonPath("$.cancelRequestedAt").isNotEmpty());

        assertThat(statusOf(id)).isEqualTo("PROVISIONING");
        assertThat(versionOf(id)).isEqualTo(1);
    }

    @Test
    void repeatCancelWhileProvisioningIs202WithoutAWrite() throws Exception {
        String id = createOrder("partner-a");
        setStatus(id, OrderStatus.PROVISIONING);
        String first = cancel("partner-a", id).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        cancel("partner-a", id)
                .andExpect(status().isAccepted())
                .andExpect(content().json(first, true));

        assertThat(versionOf(id)).as("the repeat must not write").isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(names = {"ACTIVE", "FAILED"})
    void cancelFromAFinalStateIs409IllegalTransition(OrderStatus from) throws Exception {
        String id = createOrder("partner-a");
        setStatus(id, from);

        cancel("partner-a", id)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(ProblemTypes.ILLEGAL_STATUS_TRANSITION.toString()))
                .andExpect(jsonPath("$.orderId").value(id))
                .andExpect(jsonPath("$.currentStatus").value(from.name()))
                .andExpect(jsonPath("$.targetStatus").value("CANCELLED"));

        assertThat(statusOf(id)).isEqualTo(from.name());
    }

    @Test
    void anotherPartnerCannotCancelAndTheOrderIsUntouched() throws Exception {
        String id = createOrder("partner-a");

        cancel("partner-b", id)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(ProblemTypes.ORDER_NOT_FOUND.toString()))
                .andExpect(jsonPath("$.orderId").value(id));

        assertThat(statusOf(id)).isEqualTo("CREATED");
        assertThat(versionOf(id)).isZero();
    }

    @Test
    void unknownOrderIs404() throws Exception {
        cancel("partner-a", UUID.randomUUID().toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(ProblemTypes.ORDER_NOT_FOUND.toString()));
    }

    @Test
    void nonUuidIdIs400() throws Exception {
        cancel("partner-a", "abc")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(ProblemTypes.INVALID_PARAMETER.toString()));
    }

    @Test
    void missingPartnerHeaderIs401() throws Exception {
        mockMvc.perform(post("/orders/{id}/cancel", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
