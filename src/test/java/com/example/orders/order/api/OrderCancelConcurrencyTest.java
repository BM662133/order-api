package com.example.orders.order.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.orders.order.app.IdempotencyKey;
import com.example.orders.support.Concurrently;
import com.example.orders.tenant.PartnerAuthenticationInterceptor;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderCancelConcurrencyTest {

    private static final int THREADS = 8;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    private static HttpHeaders partnerA() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(PartnerAuthenticationInterceptor.PARTNER_HEADER, "partner-a");
        return headers;
    }

    @Test
    void concurrentCancelsAllSucceedAndWriteExactlyOnce() throws Exception {
        HttpHeaders createHeaders = partnerA();
        createHeaders.setContentType(MediaType.APPLICATION_JSON);
        createHeaders.set(IdempotencyKey.HEADER, UUID.randomUUID().toString());
        ResponseEntity<String> created = restTemplate.postForEntity("/partners/partner-a/orders",
                new HttpEntity<>("""
                        {"msisdn": "+447700900123", "planCode": "BASIC_5G", "simType": "ESIM"}""", createHeaders),
                String.class);
        String id = JsonPath.read(created.getBody(), "$.id");

        List<ResponseEntity<String>> responses = Concurrently.run(THREADS, i -> () ->
                restTemplate.postForEntity("/orders/" + id + "/cancel", new HttpEntity<>(partnerA()), String.class));

        assertThat(responses).allSatisfy(r -> {
            assertThat(r.getStatusCode().value()).isEqualTo(200);
            assertThat((String) JsonPath.read(r.getBody(), "$.status")).isEqualTo("CANCELLED");
        });
        Long version = jdbc.queryForObject("SELECT version FROM orders WHERE id = ?", Long.class, UUID.fromString(id));
        assertThat(version).as("eight callers, one real write").isEqualTo(1);
    }
}
