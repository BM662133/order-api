package com.example.orders;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.example.orders.outbox.OutboxTransport;
import com.example.orders.outbox.kafka.KafkaTransport;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderApiApplicationTests {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ApplicationContext context;

    @Test
    void healthEndpointReportsUp() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    /** spring-kafka is on the classpath, but the default mode must start and run without any broker. */
    @Test
    void defaultModeDeliversInProcessWithNoKafkaBeans() {
        assertThat(context.getBean(OutboxTransport.class).getClass().getSimpleName()).isEqualTo("InProcessTransport");
        assertThat(context.getBeansOfType(KafkaTransport.class)).isEmpty();
        assertThat(context.getBeansOfType(NewTopic.class)).isEmpty();
    }
}
