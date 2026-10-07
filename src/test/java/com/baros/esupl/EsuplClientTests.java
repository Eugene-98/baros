package com.baros.esupl;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class EsuplClientTests {
    private HttpServer server;
    private EsuplClient client;
    private final AtomicReference<String> response = new AtomicReference<>();
    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 6, 3, 0);

    @BeforeEach void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/teams/1/sales", exchange -> {
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        client = new EsuplClient("http://127.0.0.1:" + server.getAddress().getPort(), "test", 1);
    }
    @AfterEach void stopServer() { server.stop(0); }

    @Test void strictReadsDistinguishEmptyDayFromMissingData() {
        response.set("{\"data\":[]}");
        assertThat(client.getSalesForRangeStrict(START, START.plusDays(1))).isEmpty();
        response.set("{}");
        assertThatThrownBy(() -> client.getSalesForRangeStrict(START, START.plusDays(1))).isInstanceOf(IllegalStateException.class);
    }

    @Test void readsMoneyAndDatesAndRejectsDuplicateIds() {
        String sale = """
                {"id":10,"is_paid":true,"is_deleted":false,"event_date":"2026-10-06T20:00:00+03:00",
                 "total_discounts":[{"id":1,"name":"Порча","amount":25.50}]}
                """;
        response.set("{\"data\":[" + sale + "]}");
        var result = client.getSalesForRangeStrict(START, START.plusDays(1));
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().totalDiscounts().getFirst().amount()).isEqualByComparingTo("25.50");
        assertThat(result.getFirst().eventDate().toInstant()).isEqualTo(java.time.Instant.parse("2026-10-06T17:00:00Z"));
        response.set("{\"data\":[" + sale + "," + sale + "]}");
        assertThatThrownBy(() -> client.getSalesForRangeStrict(START, START.plusDays(1))).isInstanceOf(IllegalStateException.class);
    }
}
