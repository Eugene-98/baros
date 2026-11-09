package com.baros.telegram;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.*;

class TelegramManagementClientTests {
    @Test void telegramWireFormatReceivesCallbacksAndSendsInlineButtonsAndDismissal() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Map<String, String> received = new ConcurrentHashMap<>();
        server.createContext("/bottest/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            received.put(path, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String response;
            if (path.endsWith("getUpdates")) {
                received.put("query", URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
                response = """
                        {"ok":true,"result":[{"update_id":42,"callback_query":{"id":"cq-1",
                          "from":{"id":123,"first_name":"Женя","is_bot":false},
                          "message":{"message_id":20,"chat":{"id":123,"type":"private"},"text":"Категория"},
                          "data":"assign:28:2:0"}}]}
                        """;
            } else response = "{\"ok\":true,\"result\":true}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            var client = new TelegramClient("http://127.0.0.1:" + server.getAddress().getPort(), "test");
            var update = client.getUpdates(40).getFirst();
            assertThat(update.updateId()).isEqualTo(42);
            assertThat(update.message()).isNull();
            assertThat(update.callbackQuery().data()).isEqualTo("assign:28:2:0");
            assertThat(update.callbackQuery().from().id()).isEqualTo(123);
            assertThat(received.get("query")).contains("offset=40", "allowed_updates=[\"message\",\"callback_query\"]");
            client.sendInlineMessage(123L, "Выбери категорию", new TelegramClient.InlineKeyboardMarkup(
                    List.of(List.of(new TelegramClient.InlineButton("Продукты", "assign:28:2:0")))));
            assertThat(received.get("/bottest/sendMessage")).contains("\"chat_id\":123", "\"reply_markup\"", "\"inline_keyboard\"", "\"callback_data\":\"assign:28:2:0\"");
            client.answerCallback("cq-1");
            assertThat(received.get("/bottest/answerCallbackQuery")).contains("\"callback_query_id\":\"cq-1\"");
        } finally { server.stop(0); }
    }
}
