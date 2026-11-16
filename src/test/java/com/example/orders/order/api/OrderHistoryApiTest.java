package com.example.orders.order.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.orders.common.error.ProblemTypes;
import com.example.orders.order.app.IdempotencyKey;
import com.example.orders.outbox.OutboxRelay;
import com.example.orders.support.OutboxTestSupport;
import com.example.orders.support.StatusHistory;
import com.example.orders.tenant.PartnerAuthenticationInterceptor;
import com.jayway.jsonpath.JsonPath;

/** GET /orders/{id}/history. Timelines for every provisioning path are asserted in ProvisioningFlowTest. */
@SpringBootTest
@AutoConfigureMockMvc
class OrderHistoryApiTest {

    private static final String PARTNER = PartnerAuthenticationInterceptor.PARTNER_HEADER;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OutboxRelay relay;

    private ResultActions create(String key, String msisdn) throws Exception {
        return mockMvc.perform(post("/partners/{partnerId}/orders", "partner-a")
                .header(PARTNER, "partner-a")
                .header(IdempotencyKey.HEADER, key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"msisdn": "%s", "planCode": "BASIC_5G", "simType": "ESIM"}""".formatted(msisdn)));
    }

    private String createOrder(String msisdn) throws Exception {
        String body = create(UUID.randomUUID().toString(), msisdn)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private ResultActions history(String partner, String id) throws Exception {
        return mockMvc.perform(get("/orders/{id}/history", id).header(PARTNER, partner));
    }

    @Test
    void aProvisionedOrderShowsItsWholeTimelineOldestFirst() throws Exception {
        OutboxTestSupport.drain(relay);
        String id = createOrder(OutboxTestSupport.uniqueMsisdn("1234"));
        relay.relayDue();

        history("partner-a", id)
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.orderId").value(id))
                .andExpect(jsonPath("$.history", hasSize(4)))
                .andExpect(jsonPath("$.history[0].from").value(nullValue()))
                .andExpect(jsonPath("$.history[0].to").value("CREATED"))
                .andExpect(jsonPath("$.history[0].triggeredBy").value("CLIENT"))
                .andExpect(jsonPath("$.history[0].reason").value(nullValue()))
                .andExpect(jsonPath("$.history[0].occurredAt").isNotEmpty())
                .andExpect(jsonPath("$.history[3].from").value("PROVISIONING"))
                .andExpect(jsonPath("$.history[3].to").value("ACTIVE"))
                .andExpect(jsonPath("$.history[3].triggeredBy").value("SYSTEM"));
    }

    @Test
    void theLastEntryMatchesTheOrdersUpdatedAt() throws Exception {
        String id = createOrder("+447700900123");
        mockMvc.perform(post("/orders/{id}/cancel", id).header(PARTNER, "partner-a")).andExpect(status().isOk());

        String order = mockMvc.perform(get("/orders/{id}", id).header(PARTNER, "partner-a"))
                .andReturn().getResponse().getContentAsString();
        String timeline = history("partner-a", id)
                .andExpect(jsonPath("$.history", hasSize(2)))
                .andExpect(jsonPath("$.history[1].to").value("CANCELLED"))
                .andExpect(jsonPath("$.history[1].triggeredBy").value("CLIENT"))
                .andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(timeline, "$.history[1].occurredAt"))
                .isEqualTo(JsonPath.read(order, "$.updatedAt"));
    }

    @Test
    void replaysAndRejectedCreatesAddNoHistory() throws Exception {
        String key = UUID.randomUUID().toString();
        String id = JsonPath.read(create(key, "+447700900123").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        int before = StatusHistory.count(jdbc);

        create(key, "+447700900123").andExpect(status().isCreated());
        create(key, "+447700900999").andExpect(status().isUnprocessableEntity());
        create(UUID.randomUUID().toString(), "nope").andExpect(status().isBadRequest());

        assertThat(StatusHistory.count(jdbc)).isEqualTo(before);
        assertThat(StatusHistory.timeline(jdbc, id)).containsExactly("null->CREATED by CLIENT");
    }

    @Test
    void anotherPartnersOrderIs404() throws Exception {
        String id = createOrder("+447700900123");

        history("partner-b", id)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(ProblemTypes.ORDER_NOT_FOUND.toString()))
                .andExpect(jsonPath("$.orderId").value(id));
    }

    @Test
    void unknownOrderIs404() throws Exception {
        history("partner-a", UUID.randomUUID().toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(ProblemTypes.ORDER_NOT_FOUND.toString()));
    }

    @Test
    void nonUuidIdIs400() throws Exception {
        history("partner-a", "abc")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(ProblemTypes.INVALID_PARAMETER.toString()));
    }

    @Test
    void missingPartnerHeaderIs401() throws Exception {
        mockMvc.perform(get("/orders/{id}/history", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }
}
