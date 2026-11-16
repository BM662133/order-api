package com.example.orders.outbox.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.orders.order.app.IdempotencyKey;
import com.example.orders.order.app.OrderEvents;
import com.example.orders.outbox.EventEnvelope;
import com.example.orders.outbox.OutboxProperties;
import com.example.orders.outbox.OutboxRelay;
import com.example.orders.provisioning.FakeProvisioningGateway;
import com.example.orders.provisioning.LineRequest;
import com.example.orders.support.OutboxTestSupport;
import com.example.orders.support.StatusHistory;
import com.example.orders.tenant.PartnerAuthenticationInterceptor;
import com.jayway.jsonpath.JsonPath;

/**
 * The "kafka" profile against an in-JVM broker: relay -> topic -> listener -> worker. The relay is still
 * driven by hand (scheduling is off in tests); consumption is asynchronous, hence Awaitility.
 * {@code @DirtiesContext} stops the broker and the listener once done.
 */
@SpringBootTest(properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
@ActiveProfiles("kafka")
@EmbeddedKafka(partitions = 3)
@AutoConfigureMockMvc
@DirtiesContext
class OutboxKafkaIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private OutboxProperties outboxProperties;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @MockitoSpyBean
    private FakeProvisioningGateway gateway;

    @BeforeEach
    void publishLeftoverEvents() {
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

    private Map<String, Object> order(String id) {
        return jdbc.queryForMap("SELECT status, failure_reason FROM orders WHERE id = ?", UUID.fromString(id));
    }

    private Map<String, Object> outboxRow(String orderId) {
        return jdbc.queryForMap("SELECT * FROM outbox_events WHERE aggregate_id = ?", UUID.fromString(orderId));
    }

    private void awaitStatus(String id, String status) {
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(order(id)).containsEntry("status", status));
    }

    private static LineRequest forOrder(String id) {
        UUID orderId = UUID.fromString(id);
        return argThat(line -> line != null && line.orderId().equals(orderId));
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    /** Reads {@code topic} from the beginning with a fresh consumer group until a record matches. */
    private ConsumerRecord<String, String> awaitRecord(String topic, Predicate<ConsumerRecord<String, String>> match) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (Consumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (match.test(record)) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("No matching record on " + topic + " within " + TIMEOUT);
    }

    @Test
    void anOrderIsProvisionedThroughKafka() throws Exception {
        String id = createOrder(OutboxTestSupport.uniqueMsisdn("1234"));

        relay.relayDue();

        assertThat(outboxRow(id)).as("PROCESSED as soon as the broker acknowledged")
                .containsEntry("status", "PROCESSED");
        awaitStatus(id, "ACTIVE");
        verify(gateway, times(1)).activate(forOrder(id));
        assertThat(StatusHistory.timeline(jdbc, id)).hasSize(4);
    }

    @Test
    void aWholeBatchIsPublishedInOneRelayPass() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            ids.add(createOrder(OutboxTestSupport.uniqueMsisdn("1234")));
        }

        assertThat(relay.relayDue()).isEqualTo(50);

        assertThat(ids).allSatisfy(id -> assertThat(outboxRow(id)).containsEntry("status", "PROCESSED"));
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(ids).allSatisfy(id -> assertThat(order(id)).containsEntry("status", "ACTIVE")));
    }

    @Test
    void theRecordIsKeyedByPartnerAndMsisdnWithMetadataHeaders() throws Exception {
        String msisdn = OutboxTestSupport.uniqueMsisdn("1234");
        String id = createOrder(msisdn);
        String eventId = outboxRow(id).get("id").toString();

        relay.relayDue();

        ConsumerRecord<String, String> record =
                awaitRecord(OutboxKafkaCodec.TOPIC, r -> eventId.equals(header(r, OutboxKafkaCodec.EVENT_ID)));
        assertThat(record.key()).isEqualTo("partner-a:" + msisdn.substring(1));
        assertThat(header(record, OutboxKafkaCodec.EVENT_TYPE)).isEqualTo(OrderEvents.ORDER_CREATED);
        assertThat(header(record, OutboxKafkaCodec.AGGREGATE_ID)).isEqualTo(id);
        assertThat(header(record, OutboxKafkaCodec.PARTNER_ID)).isEqualTo("partner-a");
        assertThat((String) JsonPath.read(record.value(), "$.orderId")).isEqualTo(id);
    }

    @Test
    void aRecordThatKeepsFailingIsDeadLetteredAndTheOrderFails() throws Exception {
        String id = createOrder(OutboxTestSupport.uniqueMsisdn(FakeProvisioningGateway.UNAVAILABLE_SUFFIX));
        String eventId = outboxRow(id).get("id").toString();

        relay.relayDue();

        awaitStatus(id, "FAILED");
        assertThat(order(id)).containsEntry("failure_reason", "PROVISIONING_UNAVAILABLE");
        assertThat(outboxRow(id)).as("publishing succeeded; the consumer gave up")
                .containsEntry("status", "PROCESSED");
        verify(gateway, times(outboxProperties.maxAttempts())).activate(forOrder(id));
        awaitRecord(OutboxKafkaCodec.DEAD_LETTER_TOPIC, r -> eventId.equals(header(r, OutboxKafkaCodec.EVENT_ID)));
    }

    /**
     * Sends the same record again, then a second order with the same key. Same key means same partition,
     * so once the second order is processed the duplicate has been consumed too.
     */
    @Test
    void aDuplicateRecordIsANoOp() throws Exception {
        String msisdn = OutboxTestSupport.uniqueMsisdn("1234");
        String id = createOrder(msisdn);
        relay.relayDue();
        awaitStatus(id, "ACTIVE");
        Map<String, Object> row = outboxRow(id);
        EventEnvelope original = new EventEnvelope((UUID) row.get("id"), (String) row.get("event_type"),
                (UUID) row.get("aggregate_id"), (String) row.get("partner_id"), (String) row.get("message_key"),
                (String) row.get("payload"));

        kafkaTemplate.send(OutboxKafkaCodec.toRecord(original)).get();
        String marker = createOrder(msisdn);
        relay.relayDue();
        awaitStatus(marker, "FAILED");

        assertThat(order(marker)).containsEntry("failure_reason", "MSISDN_IN_USE");
        verify(gateway, times(1)).activate(forOrder(id));
        assertThat(StatusHistory.timeline(jdbc, id)).hasSize(4);
    }

    @Test
    void anUnreadableRecordGoesStraightToTheDeadLetterTopic() throws Exception {
        String key = "garbage-" + UUID.randomUUID();

        kafkaTemplate.send(OutboxKafkaCodec.TOPIC, key, "{}").get();

        ConsumerRecord<String, String> deadLetter =
                awaitRecord(OutboxKafkaCodec.DEAD_LETTER_TOPIC, r -> key.equals(r.key()));
        // The listener's exception arrives wrapped in ListenerExecutionFailedException; ours is the cause.
        assertThat(header(deadLetter, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN))
                .isEqualTo(InvalidOutboxRecordException.class.getName());
    }
}
