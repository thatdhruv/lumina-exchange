package com.lumina.orderbook.integration;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole pipeline against real Kafka and Postgres: REST/SSE gateway, engine, projector,
 * snapshots and replay.
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "lumina.kafka.partitions=3",
                "lumina.engine.snapshot-every-commands=1000000",
                "lumina.gateway.book-resend-interval=1s",
                "management.server.port=0",
                "management.tracing.sampling.probability=0.0"
        })
class ExchangeIntegrationTest {

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final String SELLER_KEY = "dev-key-trader-1";
    private static final String BUYER_KEY = "dev-key-trader-2";

    @LocalServerPort
    int port;

    @Autowired
    KafkaListenerEndpointRegistry registry;

    @Autowired
    JdbcClient jdbc;

    record Response(int status, Map<String, Object> body) {
    }

    private RestClient client() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @SuppressWarnings("unchecked")
    private Response call(HttpMethod method, String path, String key, Object body, String idempotencyKey) {
        RestClient.RequestBodySpec spec = client().method(method).uri(path).headers(h -> {
            if (key != null) {
                h.set("X-API-Key", key);
            }
            if (idempotencyKey != null) {
                h.set("Idempotency-Key", idempotencyKey);
            }
        });
        if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.exchange((req, res) -> new Response(res.getStatusCode().value(), safeBody(res.bodyTo(Map.class))));
    }

    private static Map<String, Object> safeBody(Map<String, Object> m) {
        return m == null ? Map.of() : m;
    }

    private Response place(String key, String symbol, String side, String type, String price, String qty) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("symbol", symbol);
        body.put("side", side);
        body.put("type", type);
        if (price != null) {
            body.put("price", new BigDecimal(price));
        }
        body.put("quantity", new BigDecimal(qty));
        return call(HttpMethod.POST, "/api/v1/orders", key, body, null);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(String path) {
        return client().get().uri(path).retrieve().body(List.class);
    }

    private Map<String, Object> book(String symbol) {
        return client().get().uri("/api/v1/orderbook/" + symbol).retrieve().body(Map.class);
    }

    private static BigDecimal num(Object o) {
        return new BigDecimal(String.valueOf(o));
    }

    @Test
    void matchingFlowsThroughEngineProjectionAndRest() {
        Response sell = place(SELLER_KEY, "BTC-USD", "SELL", "LIMIT", "51000", "2");
        assertThat(sell.status()).isEqualTo(201);
        assertThat(sell.body()).containsEntry("status", "OPEN");

        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            List<Map<String, Object>> asks = (List<Map<String, Object>>) book("BTC-USD").get("asks");
            assertThat(asks).hasSize(1);
            assertThat(num(asks.getFirst().get("price"))).isEqualByComparingTo("51000");
            assertThat(num(asks.getFirst().get("quantity"))).isEqualByComparingTo("2");
        });

        Response buy = place(BUYER_KEY, "BTC-USD", "BUY", "LIMIT", "51000", "2");
        assertThat(buy.status()).isEqualTo(201);
        assertThat(buy.body()).containsEntry("status", "FILLED");
        assertThat(num(buy.body().get("filledQuantity"))).isEqualByComparingTo("2");
        assertThat(num(buy.body().get("remainingQuantity"))).isEqualByComparingTo("0");

        String sellId = (String) sell.body().get("id");
        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Response order = call(HttpMethod.GET, "/api/v1/orders/" + sellId, SELLER_KEY, null, null);
            assertThat(order.body()).containsEntry("status", "FILLED");
            assertThat(list("/api/v1/trades/BTC-USD")).isNotEmpty();
            assertThat(((List<?>) book("BTC-USD").get("asks"))).isEmpty();
        });
        Map<String, Object> trade = list("/api/v1/trades/BTC-USD").getFirst();
        assertThat(num(trade.get("price"))).isEqualByComparingTo("51000");
        assertThat(num(trade.get("quantity"))).isEqualByComparingTo("2");
    }

    @Test
    void cancelRemovesTheOrderFromTheBook() {
        Response bid = place(BUYER_KEY, "ETH-USD", "BUY", "LIMIT", "1000", "5");
        String id = (String) bid.body().get("id");
        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(call(HttpMethod.GET, "/api/v1/orders/" + id, BUYER_KEY, null, null).status()).isEqualTo(200));

        Response cancelled = call(HttpMethod.DELETE, "/api/v1/orders/" + id, BUYER_KEY, null, null);

        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(cancelled.body()).containsEntry("status", "CANCELLED");
        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat((List<?>) book("ETH-USD").get("bids")).isEmpty());
        assertThat(call(HttpMethod.DELETE, "/api/v1/orders/" + id, SELLER_KEY, null, null).status())
                .as("another account cannot see, let alone cancel, the order").isEqualTo(404);
    }

    @Test
    void idempotencyKeyMakesRetriesSafe() {
        Map<String, Object> body = Map.of("symbol", "SOL-USD", "side", "BUY", "type", "LIMIT",
                "price", new BigDecimal("10"), "quantity", new BigDecimal("3"));
        String key = "retry-" + UUID.randomUUID();

        Response first = call(HttpMethod.POST, "/api/v1/orders", BUYER_KEY, body, key);
        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(call(HttpMethod.GET, "/api/v1/orders/" + first.body().get("id"), BUYER_KEY, null, null).status())
                        .isEqualTo(200));
        Response second = call(HttpMethod.POST, "/api/v1/orders", BUYER_KEY, body, key);

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.body().get("id")).isEqualTo(first.body().get("id"));

        call(HttpMethod.DELETE, "/api/v1/orders/" + first.body().get("id"), BUYER_KEY, null, null);
    }

    @Test
    void edgeValidationAndAuthentication() {
        assertThat(place(null, "BTC-USD", "BUY", "LIMIT", "1", "1").status()).isEqualTo(401);
        assertThat(place("nope", "BTC-USD", "BUY", "LIMIT", "1", "1").status()).isEqualTo(401);
        assertThat(place(BUYER_KEY, "DOGE-USD", "BUY", "LIMIT", "1", "1").status()).isEqualTo(422);
        assertThat(place(BUYER_KEY, "BTC-USD", "BUY", "LIMIT", "1.005", "1").status()).as("off tick").isEqualTo(422);
        assertThat(place(BUYER_KEY, "BTC-USD", "BUY", "LIMIT", null, "1").status()).as("limit needs price").isEqualTo(422);
        assertThat(place(BUYER_KEY, "BTC-USD", "BUY", "MARKET", "5", "1").status()).as("market takes no price").isEqualTo(422);
        assertThat(place(BUYER_KEY, "BTC-USD", "BUY", "LIMIT", "1", "-1").status()).isEqualTo(400);
    }

    @Test
    void fillOrKillRejectsWithoutLiquidity() {
        Map<String, Object> body = Map.of("symbol", "SOL-USD", "side", "BUY", "type", "LIMIT", "timeInForce", "FOK",
                "price", new BigDecimal("1000"), "quantity", new BigDecimal("5"));

        Response response = call(HttpMethod.POST, "/api/v1/orders", BUYER_KEY, body, null);

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.body()).containsEntry("code", "FOK_UNFILLABLE");
    }

    @Test
    void streamDeliversSnapshotThenLiveBookAndTradeEvents() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<java.io.InputStream> stream = http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/stream/ETH-USD")).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        Thread reader = Thread.startVirtualThread(() -> {
            try (var in = new BufferedReader(new InputStreamReader(stream.body()))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("event:")) {
                        received.add(line.substring(6).trim());
                    }
                }
            } catch (Exception ignored) {
                // closed at the end of the test
            }
        });

        Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> received.contains("snapshot"));
        place(SELLER_KEY, "ETH-USD", "SELL", "LIMIT", "2000", "1");
        place(BUYER_KEY, "ETH-USD", "BUY", "LIMIT", "2000", "1");

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(received).contains("book", "trade"));
        stream.body().close();
        reader.interrupt();
    }

    @Test
    void engineRecoversFromItsSnapshotAfterARestart() {
        Response resting = place(SELLER_KEY, "SOL-USD", "SELL", "LIMIT", "77", "4");
        assertThat(resting.status()).isEqualTo(201);

        restartEngine(false);

        assertThat(jdbc.sql("SELECT count(*) FROM engine_snapshots").query(Long.class).single()).isPositive();
        Response buy = place(BUYER_KEY, "SOL-USD", "BUY", "LIMIT", "77", "4");
        assertThat(buy.status()).isEqualTo(201);
        assertThat(buy.body()).as("matched against the order that existed before the restart")
                .containsEntry("status", "FILLED");
    }

    @Test
    void engineRebuildsFromTheFullCommandLogWhenTheSnapshotIsLost() {
        Response resting = place(SELLER_KEY, "ETH-USD", "SELL", "LIMIT", "3333", "2");
        assertThat(resting.status()).isEqualTo(201);

        restartEngine(true);

        Response buy = place(BUYER_KEY, "ETH-USD", "BUY", "LIMIT", "3333", "2");
        assertThat(buy.status()).isEqualTo(201);
        assertThat(buy.body()).containsEntry("status", "FILLED");
        // replay re-emitted every old event; the projection must not have double-applied any of them
        String sellId = (String) resting.body().get("id");
        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Map<String, Object> sellOrder = call(HttpMethod.GET, "/api/v1/orders/" + sellId, SELLER_KEY, null, null).body();
            assertThat(sellOrder).containsEntry("status", "FILLED");
            assertThat(num(sellOrder.get("remainingQuantity"))).isEqualByComparingTo("0");
        });
    }

    private void restartEngine(boolean loseSnapshots) {
        var container = registry.getListenerContainer("lumina-engine");
        container.stop();
        if (loseSnapshots) {
            jdbc.sql("DELETE FROM engine_snapshots").update();
        }
        container.start();
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() ->
                container.getAssignedPartitions() != null && !container.getAssignedPartitions().isEmpty());
    }
}
