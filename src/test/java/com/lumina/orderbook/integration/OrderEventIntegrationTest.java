package com.lumina.orderbook.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lumina.orderbook.api.dto.PlaceOrderRequest;
import com.lumina.orderbook.api.dto.TradeResponse;
import com.lumina.orderbook.domain.OrderSide;
import com.lumina.orderbook.domain.OrderType;
import com.lumina.orderbook.event.KafkaTopics;
import com.lumina.orderbook.event.OrderPlacedEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class OrderEventIntegrationTest {

    private static final DockerImageName KAFKA_IMAGE = DockerImageName
            .parse("confluentinc/cp-kafka:7.5.0")
            .asCompatibleSubstituteFor("apache/kafka");

    @Container
    static KafkaContainer kafka = new KafkaContainer(KAFKA_IMAGE);

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void orderPlacedEventPublishedToKafka() {
        try (KafkaConsumer<String, OrderPlacedEvent> consumer = createOrderPlacedConsumer()) {
            consumer.subscribe(List.of(KafkaTopics.ORDERS_PLACED));

            ResponseEntity<Map> response = restTemplate.postForEntity(
                    orderUrl(),
                    new PlaceOrderRequest(
                            "BTC-USD",
                            OrderSide.BUY,
                            OrderType.LIMIT,
                            new BigDecimal("50000"),
                            new BigDecimal("1")),
                    Map.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                ConsumerRecords<String, OrderPlacedEvent> records = consumer.poll(Duration.ofMillis(500));
                assertThat(records.count()).isGreaterThan(0);
                OrderPlacedEvent event = records.iterator().next().value();
                assertThat(event.symbol()).isEqualTo("BTC-USD");
                assertThat(event.side()).isEqualTo(OrderSide.BUY);
            });
        }
    }

    @Test
    void tradeStoredInRedisAfterMatch() {
        restTemplate.postForEntity(
                orderUrl(),
                new PlaceOrderRequest(
                        "ETH-USD",
                        OrderSide.SELL,
                        OrderType.LIMIT,
                        new BigDecimal("3000"),
                        new BigDecimal("2")),
                Map.class);

        restTemplate.postForEntity(
                orderUrl(),
                new PlaceOrderRequest(
                        "ETH-USD",
                        OrderSide.BUY,
                        OrderType.LIMIT,
                        new BigDecimal("3000"),
                        new BigDecimal("2")),
                Map.class);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            ResponseEntity<TradeResponse[]> tradesResponse = restTemplate.getForEntity(
                    "http://localhost:" + port + "/api/v1/trades/ETH-USD?limit=50",
                    TradeResponse[].class);
            assertThat(tradesResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(tradesResponse.getBody()).isNotNull();
            assertThat(tradesResponse.getBody()).hasSize(1);
            assertThat(tradesResponse.getBody()[0].symbol()).isEqualTo("ETH-USD");
            assertThat(tradesResponse.getBody()[0].quantity()).isEqualByComparingTo(new BigDecimal("2"));
        });
    }

    private String orderUrl() {
        return "http://localhost:" + port + "/api/v1/orders";
    }

    private KafkaConsumer<String, OrderPlacedEvent> createOrderPlacedConsumer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.lumina.orderbook.event");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, OrderPlacedEvent.class.getName());

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        JsonDeserializer<OrderPlacedEvent> deserializer =
                new JsonDeserializer<>(OrderPlacedEvent.class, mapper);
        deserializer.addTrustedPackages("com.lumina.orderbook.event");

        return new KafkaConsumer<>(props, new StringDeserializer(), deserializer);
    }
}
