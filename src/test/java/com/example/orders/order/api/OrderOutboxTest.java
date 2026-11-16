package com.example.orders.order.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.orders.order.app.IdempotencyKey;
import com.example.orders.order.app.OrderEvents;
import com.example.orders.tenant.PartnerAuthenticationInterceptor;
import com.jayway.jsonpath.JsonPath;

/** Exactly one OrderCreated event per order: written with the order, never for a replay or a rejection. */
@SpringBootTest
@AutoConfigureMockMvc
class OrderOutboxTest {

    private static final String VALID_BODY = """
            {"msisdn": "+447700900123", "planCode": "BASIC_5G", "simType": "ESIM"}""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private ResultActions create(String key, String body) throws Exception {
        return mockMvc.perform(post("/partners/{partnerId}/orders", "partner-a")
                .header(PartnerAuthenticationInterceptor.PARTNER_HEADER, "partner-a")
                .header(IdempotencyKey.HEADER, key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String idOf(ResultActions result) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
    }

    private int eventsFor(String orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ?", Integer.class,
                UUID.fromString(orderId));
    }

    private int allEvents() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class);
    }

    @Test
    void createRecordsOnePendingOrderCreatedEvent() throws Exception {
        String id = idOf(create(UUID.randomUUID().toString(), VALID_BODY).andExpect(status().isCreated()));

        Map<String, Object> event = jdbc.queryForMap(
                "SELECT event_type, partner_id, status, attempts, payload FROM outbox_events WHERE aggregate_id = ?",
                UUID.fromString(id));
        assertThat(event)
                .containsEntry("event_type", OrderEvents.ORDER_CREATED)
                .containsEntry("partner_id", "partner-a")
                .containsEntry("status", "PENDING")
                .containsEntry("attempts", 0);
        String payload = (String) event.get("payload");
        assertThat((String) JsonPath.read(payload, "$.orderId")).isEqualTo(id);
        assertThat((String) JsonPath.read(payload, "$.partnerId")).isEqualTo("partner-a");
    }

    @Test
    void anIdempotentReplayRecordsNoSecondEvent() throws Exception {
        String key = UUID.randomUUID().toString();
        String id = idOf(create(key, VALID_BODY).andExpect(status().isCreated()));

        create(key, VALID_BODY).andExpect(status().isCreated());

        assertThat(eventsFor(id)).isEqualTo(1);
    }

    @Test
    void rejectedCreatesRecordNoEvent() throws Exception {
        String key = UUID.randomUUID().toString();
        create(key, VALID_BODY).andExpect(status().isCreated());
        int before = allEvents();

        create(UUID.randomUUID().toString(), """
                {"msisdn": "nope", "planCode": "BASIC_5G", "simType": "ESIM"}""").andExpect(status().isBadRequest());
        create(key, """
                {"msisdn": "+447700900999", "planCode": "BASIC_5G", "simType": "ESIM"}""")
                .andExpect(status().isUnprocessableEntity());

        assertThat(allEvents()).isEqualTo(before);
    }
}
