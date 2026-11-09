package com.baros.reservations;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.baros.reservations.ReservationStore.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReservationDeliveryTests {
    @TempDir Path directory;
    ReservationStore store() throws Exception {
        return new ReservationStore(new ReservationSettings(directory.resolve("delivery.sqlite").toString(), Set.of(1L), 0,
                true, 18, 2, 120, Map.of("12", 5)));
    }
    @Test void realHttpClientUsesJsonAndParsesContactsAndRateLimitsWithoutExposingToken() throws Exception {
        var requests = new CopyOnWriteArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/botFAKE_TEST_TOKEN/", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String method = exchange.getRequestURI().getPath();
            String response = method.endsWith("getUpdates")
                    ? "{\"ok\":true,\"result\":[{\"update_id\":12,\"message\":{\"contact\":{\"user_id\":101,\"phone_number\":\"375291234567\"}}}]}"
                    : method.endsWith("sendMessage")
                    ? "{\"ok\":false,\"error_code\":429,\"parameters\":{\"retry_after\":17}}"
                    : "{\"ok\":true,\"result\":{\"url\":\"\"}}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(method.endsWith("sendMessage") ? 429 : 200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var telegram = new ReservationTelegram("http://127.0.0.1:" + server.getAddress().getPort(), "FAKE_TEST_TOKEN");
            telegram.verify();
            var updates = telegram.updates(12);
            var contact = ReservationBot.map(ReservationBot.map(updates.getFirst().get("message")).get("contact"));
            assertThat(ReservationBot.id(contact, "user_id")).isEqualTo(101);
            assertThatThrownBy(() -> telegram.call("sendMessage", Map.of("chat_id", 101, "text", "Тест")))
                    .isInstanceOfSatisfying(ReservationTelegram.Failure.class, e -> {
                        assertThat(e.code).isEqualTo(429); assertThat(e.retryAfter).isEqualTo(17);
                        assertThat(e.getMessage()).doesNotContain("FAKE_TEST_TOKEN");
                    });
            assertThat(requests.toString()).contains("\"offset\":12", "\"callback_query\"", "Тест");
        } finally { server.stop(0); }
    }
    @Test void blockedRecipientDoesNotBlockOtherRecipientsAndTemporaryFailureIsRetried() throws Exception {
        var store = store();
        store.transaction(c -> {
            queue(c, "sendMessage", Map.of("chat_id", 101L, "text", "blocked"));
            queue(c, "sendMessage", Map.of("chat_id", 102L, "text", "retry"));
            queue(c, "sendMessage", Map.of("chat_id", 103L, "text", "delivered"));
            return null;
        });
        var telegram = mock(ReservationTelegram.class);
        when(telegram.call(eq("sendMessage"), anyMap())).thenAnswer(invocation -> {
            long recipient = ReservationBot.id(invocation.getArgument(1), "chat_id");
            if (recipient == 101) throw new ReservationTelegram.Failure(403, 0);
            if (recipient == 102) throw new ReservationTelegram.Failure(503, 1);
            return Map.of();
        });
        new ReservationPollingService(telegram, store, mock(ReservationBot.class)).flush();
        var remaining = store.transaction(c -> rows(c, "SELECT * FROM reservation_outbox"));
        assertThat(remaining).hasSize(1);
        assertThat(str(remaining.getFirst(), "body")).contains("retry");
        assertThat(number(remaining.getFirst(), "next_attempt")).isGreaterThan(Instant.now().getEpochSecond());
        assertThat(number(remaining.getFirst(), "attempts")).isEqualTo(1);
        verify(telegram, times(3)).call(eq("sendMessage"), anyMap());
    }
    @Test void cancelledBookingInvalidatesOldConfirmationAndQueuedReminder() throws Exception {
        var store = store();
        store.transaction(c -> {
            execute(c, """
                    INSERT INTO reservations(guest_id,created_by,business_day,starts,ends,party,name,phone,note,status,created_at)
                    VALUES (101,101,'2026-11-09',1,2,2,'Test','+375291234567','','CONFIRMED',0)
                    """);
            var b = one(c, "SELECT * FROM reservations WHERE id=1");
            queue(c, "sendMessage", Map.of("chat_id", 101L, "text", "old confirmation")); guardLastMessage(c, b, 0);
            execute(c, "UPDATE reservations SET status='CANCELLED',version=2 WHERE id=1");
            return null;
        });
        var telegram = mock(ReservationTelegram.class);
        new ReservationPollingService(telegram, store, mock(ReservationBot.class)).flush();
        verifyNoInteractions(telegram);
        assertThat(store.outbox(Long.MAX_VALUE)).isEmpty();
    }
}
