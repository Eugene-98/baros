package com.baros.telegram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TelegramUpdateResponse(
        boolean ok,
        List<Update> result
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Update(
            @JsonProperty("update_id")
            Long updateId,
            Message message
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(
            Chat chat,
            String text
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Chat(
            Long id,
            String type
    ) {
    }
}
