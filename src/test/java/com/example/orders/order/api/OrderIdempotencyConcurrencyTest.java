package com.example.orders.order.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import org.junit.jupiter.api.AfterEach;
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
import com.example.orders.tenant.PartnerAuthenticationInterceptor;
import com.jayway.jsonpath.JsonPath;

/**
 * Real concurrent HTTP requests through Tomcat. Whether a given run exercises the lost-race branch
 * depends on timing; OrderServiceCreateTest covers that branch deterministically. This test pins down
 * the end result: one row, one order id.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderIdempotencyConcurrencyTest {

    private static final int THREADS = 8;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    private final ExecutorService pool = Executors.newFixedThreadPool(THREADS);

    @AfterEach
    void shutDown() {
        pool.shutdownNow();
    }

    /** Releases all requests at the same instant and collects the responses. */
    private List<ResponseEntity<String>> race(String key, IntFunction<String> bodyForThread) throws Exception {
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            String body = bodyForThread.apply(i);
            futures.add(pool.submit(() -> {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.set(PartnerAuthenticationInterceptor.PARTNER_HEADER, "partner-a");
                headers.set(IdempotencyKey.HEADER, key);
                ready.countDown();
                go.await();
                return restTemplate.postForEntity("/partners/partner-a/orders", new HttpEntity<>(body, headers),
                        String.class);
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        List<ResponseEntity<String>> responses = new ArrayList<>();
        for (Future<ResponseEntity<String>> future : futures) {
            responses.add(future.get(30, TimeUnit.SECONDS));
        }
        return responses;
    }

    private int rowsFor(String key) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE partner_id = 'partner-a' AND idempotency_key = ?",
                Integer.class, key);
    }

    @Test
    void identicalConcurrentRequestsCreateExactlyOneOrder() throws Exception {
        String key = UUID.randomUUID().toString();

        List<ResponseEntity<String>> responses = race(key, i -> """
                {"msisdn": "+447700900123", "planCode": "BASIC_5G", "simType": "ESIM"}""");

        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode().value()).isEqualTo(201));
        assertThat(responses.stream().map(r -> (String) JsonPath.read(r.getBody(), "$.id")).distinct()).hasSize(1);
        assertThat(responses.stream()
                .filter(r -> !r.getHeaders().containsKey(OrderController.IDEMPOTENT_REPLAYED_HEADER)))
                .as("exactly one response is the original, the rest are replays")
                .hasSize(1);
        assertThat(rowsFor(key)).isEqualTo(1);
    }

    @Test
    void concurrentDifferentRequestsWithOneKeyCreateOneOrderAndRejectTheRest() throws Exception {
        String key = UUID.randomUUID().toString();

        // Each thread asks for a different MSISDN, so all requests differ.
        List<ResponseEntity<String>> responses = race(key, i -> """
                {"msisdn": "+44770090010%d", "planCode": "BASIC_5G", "simType": "ESIM"}""".formatted(i));

        assertThat(responses.stream().filter(r -> r.getStatusCode().value() == 201)).hasSize(1);
        assertThat(responses.stream().filter(r -> r.getStatusCode().value() == 422)).hasSize(THREADS - 1);
        assertThat(rowsFor(key)).isEqualTo(1);
    }
}
