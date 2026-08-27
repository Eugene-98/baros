package com.baros.telegram;

import com.baros.sales.SalesAnalyticsService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

@Service
public class TelegramCommandHandler {

    private static final DateTimeFormatter RU_DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private static final DateTimeFormatter RU_MONTH_FORMAT =
            DateTimeFormatter.ofPattern("MM.yyyy");

    private static final Map<String, Integer> RU_MONTHS = Map.ofEntries(
            Map.entry("январь", 1),
            Map.entry("января", 1),
            Map.entry("февраль", 2),
            Map.entry("февраля", 2),
            Map.entry("март", 3),
            Map.entry("марта", 3),
            Map.entry("апрель", 4),
            Map.entry("апреля", 4),
            Map.entry("май", 5),
            Map.entry("мая", 5),
            Map.entry("июнь", 6),
            Map.entry("июня", 6),
            Map.entry("июль", 7),
            Map.entry("июля", 7),
            Map.entry("август", 8),
            Map.entry("августа", 8),
            Map.entry("сентябрь", 9),
            Map.entry("сентября", 9),
            Map.entry("октябрь", 10),
            Map.entry("октября", 10),
            Map.entry("ноябрь", 11),
            Map.entry("ноября", 11),
            Map.entry("декабрь", 12),
            Map.entry("декабря", 12)
    );

    private final SalesAnalyticsService salesAnalyticsService;

    public TelegramCommandHandler(SalesAnalyticsService salesAnalyticsService) {
        this.salesAnalyticsService = salesAnalyticsService;
    }

    public String handle(String text) {
        if (text == null || text.isBlank()) {
            return help();
        }

        String rawText = text.trim();
        String normalizedText = rawText.toLowerCase(Locale.ROOT);

        return switch (normalizedText) {
            case "📊 сегодня", "сегодня" -> salesAnalyticsService.formatTodaySummary();
            case "📆 вчера", "вчера" -> salesAnalyticsService.formatYesterdaySummary();
            case "🗓 этот месяц", "этот месяц", "текущий месяц" ->
                    salesAnalyticsService.formatCurrentMonthSummary();
            case "⬅️ прошлый месяц", "прошлый месяц" ->
                    salesAnalyticsService.formatPreviousMonthSummary();
            case "ℹ️ помощь", "помощь" -> help();
            default -> handleCommandOrMonth(rawText);
        };
    }

    private String handleCommandOrMonth(String rawText) {
        String normalizedText = rawText.toLowerCase(Locale.ROOT);

        if (looksLikeMonthInput(normalizedText)) {
            try {
                YearMonth month = parseMonth(normalizedText);
                return salesAnalyticsService.formatMonthSummary(month);
            } catch (DateTimeParseException ignored) {
                return monthHelp();
            }
        }

        String[] parts = rawText.trim().split("\\s+");
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
            return salesAnalyticsService.formatCurrentMonthSummary();
        }

        String monthArgument = String.join(
                " ",
                Arrays.copyOfRange(parts, 1, parts.length)
        );

        try {
            YearMonth month = parseMonth(monthArgument);
            return salesAnalyticsService.formatMonthSummary(month);
        } catch (DateTimeParseException exception) {
            return monthHelp();
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
        String normalizedValue = value.trim().toLowerCase(Locale.ROOT);

        try {
            return YearMonth.parse(normalizedValue);
        } catch (DateTimeParseException ignored) {
            // пробуем следующий формат
        }

        try {
            return YearMonth.parse(normalizedValue, RU_MONTH_FORMAT);
        } catch (DateTimeParseException ignored) {
            // пробуем русское название месяца
        }

        return parseRussianMonth(normalizedValue);
    }

    private YearMonth parseRussianMonth(String value) {
        String[] parts = value.trim().split("\\s+");

        if (parts.length == 0) {
            throw new DateTimeParseException("Empty month", value, 0);
        }

        Integer month = RU_MONTHS.get(parts[0]);

        if (month == null) {
            throw new DateTimeParseException("Unknown month", value, 0);
        }

        int year = YearMonth.now().getYear();

        if (parts.length >= 2) {
            try {
                year = Integer.parseInt(parts[1]);
            } catch (NumberFormatException exception) {
                throw new DateTimeParseException("Invalid year", value, 0);
            }
        }

        return YearMonth.of(year, month);
    }

    private boolean looksLikeMonthInput(String value) {
        String trimmedValue = value.trim();

        if (trimmedValue.matches("\\d{4}-\\d{2}")) {
            return true;
        }

        if (trimmedValue.matches("\\d{2}\\.\\d{4}")) {
            return true;
        }

        String firstWord = trimmedValue.split("\\s+")[0];

        return RU_MONTHS.containsKey(firstWord);
    }

    private String monthHelp() {
        return """
                Не понял месяц.

                Можно ввести так:
                07.2026
                2026-07
                июль 2026
                июль
                """;
    }

    private String help() {
        return """
                Baros Bot

                Основные кнопки:
                📊 Сегодня — отчет за текущий барный день
                📆 Вчера — отчет за прошлый барный день
                🗓 Этот месяц — отчет за текущий месяц
                ⬅️ Прошлый месяц — отчет за прошлый месяц

                Можно также просто отправить месяц:
                07.2026
                2026-07
                июль 2026

                Дополнительные команды:
                /day 28.07.2026
                /month 07.2026
                /help
                """;
    }
}