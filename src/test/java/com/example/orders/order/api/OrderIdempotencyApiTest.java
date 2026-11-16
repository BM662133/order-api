package com.example.orders.order.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.example.orders.common.error.ProblemTypes;
import com.example.orders.order.app.IdempotencyKey;
import com.example.orders.tenant.PartnerAuthenticationInterceptor;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
class OrderIdempotencyApiTest {

    private static final String BODY = """
            {"msisdn": "+447700900123", "planCode": "BASIC_5G", "simType": "ESIM"}""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private static MockHttpServletRequestBuilder createRequest(String partner, String body) {
        return post("/partners/{partnerId}/orders", partner)
                .header(PartnerAuthenticationInterceptor.PARTNER_HEADER, partner)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private ResultActions create(String partner, String key, String body) throws Exception {
        return mockMvc.perform(createRequest(partner, body).header(IdempotencyKey.HEADER, key));
    }

    private int rowsFor(String partner, String key) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE partner_id = ? AND idempotency_key = ?",
                Integer.class, partner, key);
    }

    private int totalRows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class);
    }

    private static String idOf(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }

    // ---------- header validation ----------

    @Test
    void missingKeyIs400AndNothingIsSaved() throws Exception {
        int before = totalRows();

        mockMvc.perform(createRequest("partner-a", BODY))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(ProblemTypes.INVALID_IDEMPOTENCY_KEY.toString()))
                .andExpect(jsonPath("$.title").value("Invalid idempotency key"))
                .andExpect(jsonPath("$.detail").value("Missing Idempotency-Key header."));

        assertThat(totalRows()).isEqualTo(before);
    }

    @Test
    void keyOverMaxLengthIs400() throws Exception {
        create("partner-a", "a".repeat(IdempotencyKey.MAX_LENGTH + 1), BODY)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(ProblemTypes.INVALID_IDEMPOTENCY_KEY.toString()));
    }

    @Test
    void keyWithInnerSpaceIs400() throws Exception {
        create("partner-a", "has space", BODY)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(ProblemTypes.INVALID_IDEMPOTENCY_KEY.toString()));
    }

    @Test
    void keyOfExactlyMaxLengthIsAccepted() throws Exception {
        String key = UUID.randomUUID().toString().replace("-", "").repeat(4).substring(0, IdempotencyKey.MAX_LENGTH);
        create("partner-a", key, BODY).andExpect(status().isCreated());
    }

    // ---------- replay ----------

    @Test
    void retryWithSameKeyAndBodyReplaysTheSameOrder() throws Exception {
        String key = newKey();

        MvcResult first = create("partner-a", key, BODY)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(OrderController.IDEMPOTENT_REPLAYED_HEADER))
                .andReturn();

        create("partner-a", key, BODY)
                .andExpect(status().isCreated())
                .andExpect(header().string(OrderController.IDEMPOTENT_REPLAYED_HEADER, "true"))
                .andExpect(header().string("Location", first.getResponse().getHeader("Location")))
                .andExpect(content().json(first.getResponse().getContentAsString(), true));

        assertThat(rowsFor("partner-a", key)).isEqualTo(1);
    }

    @Test
    void retryThatIsTheSameRequestWrittenDifferentlyIsStillAReplay() throws Exception {
        String key = newKey();
        String id = idOf(create("partner-a", key, BODY).andExpect(status().isCreated()).andReturn());

        // No '+', different key order, extra whitespace: same normalized request.
        create("partner-a", key, """
                {
                  "simType" : "ESIM",
                  "planCode": "BASIC_5G",
                  "msisdn"  : "447700900123"
                }""")
                .andExpect(status().isCreated())
                .andExpect(header().string(OrderController.IDEMPOTENT_REPLAYED_HEADER, "true"))
                .andExpect(jsonPath("$.id").value(id));

        assertThat(rowsFor("partner-a", key)).isEqualTo(1);
    }

    @Test
    void sameKeyForADifferentRequestIs422WithTheOriginalOrderId() throws Exception {
        String key = newKey();
        String id = idOf(create("partner-a", key, BODY).andExpect(status().isCreated()).andReturn());

        create("partner-a", key, """
                {"msisdn": "+447700900123", "planCode": "UNLIMITED", "simType": "ESIM"}""")
                .andExpect(status().is(422))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(ProblemTypes.IDEMPOTENCY_KEY_REUSED.toString()))
                .andExpect(jsonPath("$.title").value("Idempotency key reused"))
                .andExpect(jsonPath("$.orderId").value(id));

        assertThat(rowsFor("partner-a", key)).isEqualTo(1);
    }

    @Test
    void keysAreScopedPerPartner() throws Exception {
        String key = newKey();

        String idA = idOf(create("partner-a", key, BODY).andExpect(status().isCreated()).andReturn());
        String idB = idOf(create("partner-b", key, BODY)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(OrderController.IDEMPOTENT_REPLAYED_HEADER))
                .andReturn());

        assertThat(idB).isNotEqualTo(idA);
        assertThat(rowsFor("partner-a", key)).isEqualTo(1);
        assertThat(rowsFor("partner-b", key)).isEqualTo(1);
    }

    @Test
    void aRejectedRequestDoesNotConsumeTheKey() throws Exception {
        String key = newKey();

        create("partner-a", key, """
                {"msisdn": "nope", "planCode": "BASIC_5G", "simType": "ESIM"}""")
                .andExpect(status().isBadRequest());

        create("partner-a", key, BODY)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(OrderController.IDEMPOTENT_REPLAYED_HEADER));
    }

    // ---------- checks that depend on database state run after the replay lookup ----------

    @Test
    void aRetryAfterThePlanWasDeactivatedStillReplays() throws Exception {
        String plan = "TMP_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        jdbc.update("INSERT INTO plans (code, name, active) VALUES (?, 'Temporary', TRUE)", plan);
        String body = """
                {"msisdn": "+447700900123", "planCode": "%s", "simType": "ESIM"}""".formatted(plan);
        String key = newKey();
        String id = idOf(create("partner-a", key, body).andExpect(status().isCreated()).andReturn());
        jdbc.update("UPDATE plans SET active = FALSE WHERE code = ?", plan);

        create("partner-a", key, body)
                .andExpect(status().isCreated())
                .andExpect(header().string(OrderController.IDEMPOTENT_REPLAYED_HEADER, "true"))
                .andExpect(jsonPath("$.id").value(id));

        create("partner-a", newKey(), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(ProblemTypes.VALIDATION_ERROR.toString()))
                .andExpect(jsonPath("$.detail").value("Request has 1 invalid field(s)."))
                .andExpect(jsonPath("$.errors[0].field").value("planCode"))
                .andExpect(jsonPath("$.errors[0].message").value("must be an active plan"));
    }

    // ---------- key expiry ----------

    private void expire(String partner, String key) {
        jdbc.update("UPDATE idempotency_keys SET expires_at = ? WHERE partner_id = ? AND idempotency_key = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), partner, key);
    }

    private static Instant instant(Object timestampWithTimeZone) {
        return ((OffsetDateTime) timestampWithTimeZone).toInstant();
    }

    @Test
    void aKeyIsReservedForTheConfiguredTtlFromCreation() throws Exception {
        String key = newKey();
        create("partner-a", key, BODY).andExpect(status().isCreated());

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT k.expires_at, o.created_at FROM idempotency_keys k JOIN orders o ON o.id = k.order_id
                WHERE k.partner_id = 'partner-a' AND k.idempotency_key = ?""", key);
        assertThat(instant(row.get("expires_at"))).isEqualTo(instant(row.get("created_at")).plus(Duration.ofHours(24)));
    }

    @Test
    void afterExpiryTheSameRequestCreatesANewOrder() throws Exception {
        String key = newKey();
        String first = idOf(create("partner-a", key, BODY).andExpect(status().isCreated()).andReturn());
        expire("partner-a", key);

        String second = idOf(create("partner-a", key, BODY)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(OrderController.IDEMPOTENT_REPLAYED_HEADER))
                .andReturn());

        assertThat(second).isNotEqualTo(first);
        assertThat(rowsFor("partner-a", key)).as("both orders keep the key that created them").isEqualTo(2);
        create("partner-a", key, BODY)
                .andExpect(header().string(OrderController.IDEMPOTENT_REPLAYED_HEADER, "true"))
                .andExpect(jsonPath("$.id").value(second));
    }

    @Test
    void afterExpiryTheKeyMayBeUsedForADifferentRequest() throws Exception {
        String key = newKey();
        create("partner-a", key, BODY).andExpect(status().isCreated());
        expire("partner-a", key);

        create("partner-a", key, """
                {"msisdn": "+447700900999", "planCode": "BASIC_5G", "simType": "ESIM"}""")
                .andExpect(status().isCreated());
    }
}
