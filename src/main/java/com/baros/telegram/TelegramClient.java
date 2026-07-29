package com.baros.telegram;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;

@Service
public class TelegramClient {

    private final WebClient webClient;
    private final String botToken;

    public TelegramClient(
            @Value("${telegram.base-url}") String baseUrl,
            @Value("${telegram.bot-token}") String botToken
    ) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .build();

        this.botToken = botToken;
    }

    public boolean isConfigured() {
        return botToken != null && !botToken.isBlank();
    }

    public List<TelegramUpdateResponse.Update> getUpdates(long offset) {
        TelegramUpdateResponse response = webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/bot" + botToken + "/getUpdates")
                        .queryParam("offset", offset)
                        .queryParam("timeout", 25)
                        .build()
                )
                .retrieve()
                .bodyToMono(TelegramUpdateResponse.class)
                .block(Duration.ofSeconds(35));

        if (response == null || response.result() == null) {
            return List.of();
        }

        return response.result();
    }

    public void sendMessage(Long chatId, String text) {
        SendMessageRequest request = new SendMessageRequest(
                chatId,
                text,
                mainKeyboard()
        );

        webClient.post()
                .uri("/bot" + botToken + "/sendMessage")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(10));
    }

    private ReplyKeyboardMarkup mainKeyboard() {
        return new ReplyKeyboardMarkup(
                List.of(
                        List.of(
                                new KeyboardButton("/today"),
                                new KeyboardButton("/yesterday")
                        ),
                        List.of(
                                new KeyboardButton("/month"),
                                new KeyboardButton("/help")
                        )
                ),
                true,
                false,
                "Выбери отчет"
        );
    }

    public record SendMessageRequest(
            @JsonProperty("chat_id")
            Long chatId,

            String text,

            @JsonProperty("reply_markup")
            ReplyKeyboardMarkup replyMarkup
    ) {
    }

    public record ReplyKeyboardMarkup(
            List<List<KeyboardButton>> keyboard,

            @JsonProperty("resize_keyboard")
            boolean resizeKeyboard,

            @JsonProperty("one_time_keyboard")
            boolean oneTimeKeyboard,

            @JsonProperty("input_field_placeholder")
            String inputFieldPlaceholder
    ) {
    }

    public record KeyboardButton(
            String text
    ) {
    }
}