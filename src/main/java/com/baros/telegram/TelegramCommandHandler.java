package com.baros.telegram;

import com.baros.sales.SalesAnalyticsService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

@Service
public class TelegramCommandHandler {

    private static final DateTimeFormatter RU_DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private static final DateTimeFormatter RU_MONTH_FORMAT =
            DateTimeFormatter.ofPattern("MM.yyyy");

    private final SalesAnalyticsService salesAnalyticsService;

    public TelegramCommandHandler(SalesAnalyticsService salesAnalyticsService) {
        this.salesAnalyticsService = salesAnalyticsService;
    }

    public String handle(String text) {
        if (text == null || text.isBlank()) {
            return help();
        }

        String[] parts = text.trim().split("\\s+");

        String command = parts[0].toLowerCase(Locale.ROOT);

        int botMentionIndex = command.indexOf("@");
        if (botMentionIndex > 0) {
            command = command.substring(0, botMentionIndex);
        }

        return switch (command) {
            case "/start", "/help" -> help();
            case "/today" -> salesAnalyticsService.formatTodaySummary();
            case "/yesterday" -> salesAnalyticsService.formatYesterdaySummary();
            case "/day" -> handleDay(parts);
            case "/month" -> handleMonth(parts);
            default -> "Неизвестная команда.\n\n" + help();
        };
    }

    private String handleDay(String[] parts) {
        if (parts.length < 2) {
            return """
                    Укажи дату.

                    Примеры:
                    /day 2026-07-28
                    /day 28.07.2026
                    """;
        }

        try {
            LocalDate date = parseDate(parts[1]);
            return salesAnalyticsService.formatDaySummary(date);
        } catch (DateTimeParseException exception) {
            return """
                    Не понял дату.

                    Используй один из форматов:
                    /day 2026-07-28
                    /day 28.07.2026
                    """;
        }
    }

    private String handleMonth(String[] parts) {
        if (parts.length < 2) {
            return """
                    Укажи месяц.

                    Примеры:
                    /month 2026-07
                    /month 07.2026
                    """;
        }

        try {
            YearMonth month = parseMonth(parts[1]);
            return salesAnalyticsService.formatMonthSummary(month);
        } catch (DateTimeParseException exception) {
            return """
                    Не понял месяц.

                    Используй один из форматов:
                    /month 2026-07
                    /month 07.2026
                    """;
        }
    }

    private LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ignored) {
            return LocalDate.parse(value, RU_DATE_FORMAT);
        }
    }

    private YearMonth parseMonth(String value) {
        try {
            return YearMonth.parse(value);
        } catch (DateTimeParseException ignored) {
            return YearMonth.parse(value, RU_MONTH_FORMAT);
        }
    }

    private String help() {
        return """
                Baros Bot

                Доступные команды:

                /today — отчет за текущий барный день
                /yesterday — отчет за прошлый барный день
                /day 2026-07-28 — отчет за выбранный день
                /month 2026-07 — отчет за месяц
                /help — список команд
                """;
    }
}