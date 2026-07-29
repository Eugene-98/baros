package com.baros.telegram;

import com.baros.sales.SalesAnalyticsService;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class TelegramCommandHandler {

    private final SalesAnalyticsService salesAnalyticsService;

    public TelegramCommandHandler(SalesAnalyticsService salesAnalyticsService) {
        this.salesAnalyticsService = salesAnalyticsService;
    }

    public String handle(String text) {
        if (text == null || text.isBlank()) {
            return help();
        }

        String command = text.trim().split("\\s+")[0].toLowerCase(Locale.ROOT);

        int botMentionIndex = command.indexOf("@");
        if (botMentionIndex > 0) {
            command = command.substring(0, botMentionIndex);
        }

        return switch (command) {
            case "/start", "/help" -> help();
            case "/today" -> salesAnalyticsService.formatTodaySummary();
            default -> "Неизвестная команда.\n\n" + help();
        };
    }

    private String help() {
        return """
                Baros Bot

                Доступные команды:

                /today — отчет за текущий барный день
                /help — список команд
                """;
    }
}