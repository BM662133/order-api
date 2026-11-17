package com.example.orders;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.IntFunction;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.example.orders.order.api.OrderController;
import com.example.orders.order.app.IdempotencyKey;
import com.example.orders.outbox.OutboxRelay;
import com.example.orders.provisioning.ProvisioningSweeper;
import com.example.orders.support.Concurrently;
import com.example.orders.support.OutboxTestSupport;
import com.example.orders.support.StatusHistory;
import com.example.orders.tenant.PartnerAuthenticationInterceptor;
import com.jayway.jsonpath.JsonPath;

/**
 * The whole app on a real PostgreSQL (the "postgres" profile's database), to catch what H2 lets through:
 * migration syntax, Hibernate's schema validation, the constraint name a duplicate idempotency key
 * reports, and the hand-written queries (head-of-line {@code findDue}, the sweeper's SQL). Skipped when
 * Docker isn't available, so the rest of the suite still runs without it.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
class PostgresSmokeTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.5");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private ProvisioningSweeper sweeper;

    @BeforeEach
    void drainPendingEvents() {
        OutboxTestSupport.drain(relay);
    }

    private static HttpEntity<String> createRequest(String key, String msisdn) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(PartnerAuthenticationInterceptor.PARTNER_HEADER, "partner-a");
        headers.set(IdempotencyKey.HEADER, key);
        return new HttpEntity<>("""
                {"msisdn": "%s", "planCode": "BASIC_5G", "simType": "ESIM"}""".formatted(msisdn), headers);
    }

    private ResponseEntity<String> create(String key, String msisdn) {
        return restTemplate.postForEntity("/partners/partner-a/orders", createRequest(key, msisdn), String.class);
    }

    private String createOrder(String msisdn) {
        ResponseEntity<String> response = create(UUID.randomUUID().toString(), msisdn);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return JsonPath.read(response.getBody(), "$.id");
    }

    private String status(String id) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, UUID.fromString(id));
    }

    @Test
    void runsOnPostgresWithEveryMigrationApplied() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }
        Integer latest = jdbc.queryForObject(
                "SELECT MAX(CAST(version AS INT)) FROM flyway_schema_history WHERE success", Integer.class);
        Integer migrationFiles = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version IS NOT NULL", Integer.class);
        assertThat(latest).isEqualTo(migrationFiles);
        // Hibernate's ddl-auto=validate passed too, or the context would not have started.
    }

    @Test
    void anOrderIsProvisionedEndToEnd() {
        String id = createOrder(OutboxTestSupport.uniqueMsisdn("1234"));

        OutboxTestSupport.drain(relay);

        assertThat(status(id)).isEqualTo("ACTIVE");
        assertThat(StatusHistory.timeline(jdbc, id)).containsExactly(
                "null->CREATED by CLIENT",
                "CREATED->VALIDATED by SYSTEM",
                "VALIDATED->PROVISIONING by SYSTEM",
                "PROVISIONING->ACTIVE by SYSTEM");
    }

    /** Postgres names the violated constraint differently from H2; the lost race must still be recognised. */
    @Test
    void concurrentRequestsWithOneKeyCreateExactlyOneOrder() throws Exception {
        String key = UUID.randomUUID().toString();
        String msisdn = OutboxTestSupport.uniqueMsisdn("1234");
        IntFunction<Callable<ResponseEntity<String>>> request = i -> () -> create(key, msisdn);

        List<ResponseEntity<String>> responses = Concurrently.run(8, request);

        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode().value()).isEqualTo(201));
        assertThat(responses.stream().map(r -> (String) JsonPath.read(r.getBody(), "$.id")).distinct()).hasSize(1);
        assertThat(responses.stream()
                .filter(r -> !r.getHeaders().containsKey(OrderController.IDEMPOTENT_REPLAYED_HEADER)))
                .hasSize(1);
    }

    @Test
    void anExpiredKeyCanBeReusedForADifferentRequest() {
        String key = UUID.randomUUID().toString();
        String first = JsonPath.read(create(key, OutboxTestSupport.uniqueMsisdn("1234")).getBody(), "$.id");
        jdbc.update("UPDATE idempotency_keys SET expires_at = expires_at - INTERVAL '25' HOUR WHERE idempotency_key = ?",
                key);

        ResponseEntity<String> reuse = create(key, OutboxTestSupport.uniqueMsisdn("1234"));

        assertThat(reuse.getStatusCode().value()).isEqualTo(201);
        assertThat((String) JsonPath.read(reuse.getBody(), "$.id")).isNotEqualTo(first);
    }

    /** Head-of-line findDue: the second event for one key waits for the first, on Postgres too. */
    @Test
    void ofTwoOrdersForOneMsisdnTheSecondWaitsAndFails() {
        String msisdn = OutboxTestSupport.uniqueMsisdn("1234");
        String first = createOrder(msisdn);
        String second = createOrder(msisdn);

        assertThat(relay.relayDue()).as("one event per key per pass").isEqualTo(1);
        OutboxTestSupport.drain(relay);

        assertThat(status(first)).isEqualTo("ACTIVE");
        assertThat(status(second)).isEqualTo("FAILED");
    }

    @Test
    void theSweeperRequeuesAStalledOrder() {
        String id = createOrder(OutboxTestSupport.uniqueMsisdn("1234"));
        // Lose the event and age the order past stalled-after (15m).
        jdbc.update("UPDATE outbox_events SET status = 'PROCESSED' WHERE aggregate_id = ?", UUID.fromString(id));
        jdbc.update("UPDATE orders SET updated_at = updated_at - INTERVAL '1' HOUR WHERE id = ?", UUID.fromString(id));

        assertThat(sweeper.sweep().retried()).isEqualTo(1);
        OutboxTestSupport.drain(relay);

        assertThat(status(id)).isEqualTo("ACTIVE");
    }
}
