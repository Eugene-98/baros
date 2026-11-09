package com.baros.reservations;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.*;

@Component
@Profile("reservations")
public class ReservationTelegram {
    private final WebClient http;
    private final String token;
    static class Failure extends RuntimeException {
        final int code;
        final int retryAfter;
        Failure(int code, int retryAfter) {
            super("Telegram request failed, code " + code);
            this.code = code; this.retryAfter = retryAfter;
        }
    }
    record Response(int code, Map<String, Object> body) {}
    public ReservationTelegram(@Value("${telegram.base-url}") String baseUrl,
                               @Value("${reservations.token}") String token) {
        http = WebClient.builder().baseUrl(baseUrl).codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024)).build();
        this.token = token;
    }
    public boolean configured() { return token != null && !token.isBlank(); }
    Object call(String method, Map<String, Object> body) {
        Response response = http.post().uri("/bot" + token + "/" + method).bodyValue(body)
                .exchangeToMono(r -> r.bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                        .defaultIfEmpty(Map.of()).map(value -> new Response(r.statusCode().value(), value)))
                .block(Duration.ofSeconds(35));
        if (response == null) throw new Failure(503, 5);
        if (!Boolean.TRUE.equals(response.body().get("ok"))) {
            int code = response.body().get("error_code") instanceof Number n ? n.intValue() : response.code();
            var parameters = ReservationBot.map(response.body().get("parameters"));
            throw new Failure(code, (int) Math.max(1, ReservationBot.id(parameters, "retry_after")));
        }
        return response.body().get("result");
    }
    void verify() {
        call("getMe", Map.of());
        if (!ReservationBot.map(call("getWebhookInfo", Map.of())).getOrDefault("url", "").equals(""))
            throw new IllegalStateException("Use a new test bot without a webhook");
    }
    List<Map<String, Object>> updates(long offset) {
        Object result = call("getUpdates", Map.of("offset", offset, "timeout", 25,
                "allowed_updates", List.of("message", "callback_query")));
        if (!(result instanceof List<?> list)) throw new IllegalStateException("Invalid Telegram update response");
        return list.stream().map(ReservationBot::map).toList();
    }
}
