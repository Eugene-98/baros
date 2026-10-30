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
            String text,
            User from
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record User(Long id, @JsonProperty("first_name") String firstName,
                       @JsonProperty("last_name") String lastName, String username,
                       @JsonProperty("is_bot") boolean bot) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Chat(
            Long id,
            String type
    ) {
    }
}
