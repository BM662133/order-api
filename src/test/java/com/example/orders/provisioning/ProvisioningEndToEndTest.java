package com.example.orders.provisioning;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import com.example.orders.order.app.IdempotencyKey;
import com.example.orders.tenant.PartnerAuthenticationInterceptor;
import com.jayway.jsonpath.JsonPath;

/**
 * The only test with the scheduler on: proves the wiring (scheduling, poll interval, relay, worker)
 * without anyone calling relayDue(). {@code @DirtiesContext} closes this context afterwards so its
 * scheduler stops; every test context shares the same H2 database.
 */
@SpringBootTest(properties = {"orders.outbox.scheduling-enabled=true", "orders.outbox.poll-interval=50ms"})
@AutoConfigureMockMvc
@DirtiesContext
class ProvisioningEndToEndTest {

    private static final String PARTNER = PartnerAuthenticationInterceptor.PARTNER_HEADER;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void anOrderBecomesActiveInTheBackground() throws Exception {
        String msisdn = String.format("+4476%06d1234", ThreadLocalRandom.current().nextInt(1_000_000));
        String body = mockMvc.perform(post("/partners/{partnerId}/orders", "partner-a")
                        .header(PARTNER, "partner-a")
                        .header(IdempotencyKey.HEADER, UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"msisdn": "%s", "planCode": "BASIC_5G", "simType": "ESIM"}""".formatted(msisdn)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                mockMvc.perform(get("/orders/{id}", id).header(PARTNER, "partner-a"))
                        .andExpect(jsonPath("$.status").value("ACTIVE")));
    }
}
