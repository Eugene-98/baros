package com.baros.fund;

import com.baros.telegram.TelegramClient.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

@Service
@Profile("dvoyka")
public class FundHistoryService {
    static final String TODAY = "📅 За сегодня";
    static final String WEEK = "📆 На текущей неделе";
    static final String MONTH = "🗓 За месяц";
    static final String NEXT = "Вперёд ▶️";
    static final String PREVIOUS = "◀️ Назад";
    static final String MENU = "⬅️ Главное меню";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private final FundLedger ledger;
    private final Clock clock;
    private final int dayStartHour;

    @Autowired
    public FundHistoryService(FundLedger ledger, @Value("${bar.time-zone}") String zone,
                              @Value("${bar.day-start-hour}") int dayStartHour) {
        this(ledger, Clock.system(ZoneId.of(zone)), dayStartHour);
    }

    FundHistoryService(FundLedger ledger, Clock clock, int dayStartHour) {
        this.ledger = ledger;
        this.clock = clock;
        this.dayStartHour = dayStartHour;
    }

    public int dayStartHour() { return dayStartHour; }

    /** Null means this is not a history action. The caller checks access first. */
    public String handle(long updateId, long chatId, long userId, String text) {
        String[] parts = text.split("\\s+");
        String command = parts[0].split("@", 2)[0].toLowerCase(Locale.ROOT);
        if (text.equals("📒 История") || command.equals("/history") && parts.length == 1) {
            ledger.openHistory(chatId, userId, updateId, "menu", null, null);
            return "📒 История · выбери период.\n\nСегодня — текущий барный день.\n"
                    + "Неделя — с понедельника.\nМесяц — с 1-го числа.\n"
                    + "Начало барного дня: " + String.format(Locale.ROOT, "%02d:00", dayStartHour) + " по Минску.";
        }
        if (text.equals(NEXT) || text.equals(PREVIOUS)) {
            var view = ledger.historyView(chatId, userId);
            if (view != null && !matchesCurrentBoundary(view)) {
                ledger.openHistory(chatId, userId, updateId, "menu", null, null);
                return "Граница барного дня изменилась на " + String.format(Locale.ROOT, "%02d:00", dayStartHour)
                        + " по Минску. Выбери период истории заново.";
            }
            return render(ledger.moveHistory(chatId, userId, updateId, text.equals(NEXT) ? 1 : -1));
        }
        String period = switch (text) {
            case TODAY -> "today";
            case WEEK -> "week";
            case MONTH -> "month";
            default -> null;
        };
        if (command.equals("/history")) {
            if (parts.length != 2 || !Set.of("today", "week", "month").contains(parts[1].toLowerCase(Locale.ROOT))) {
                throw new FundLedger.InputException("Выбери период кнопкой «История» или отправь /history today, /history week, /history month.");
            }
            period = parts[1].toLowerCase(Locale.ROOT);
        }
        if (period == null) return null;
        LocalDate today = ZonedDateTime.now(clock).minusHours(dayStartHour).toLocalDate();
        LocalDate first = switch (period) {
            case "week" -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case "month" -> today.withDayOfMonth(1);
            default -> today;
        };
        Instant from = first.atTime(dayStartHour, 0).atZone(clock.getZone()).toInstant();
        Instant until = today.plusDays(1).atTime(dayStartHour, 0).atZone(clock.getZone()).toInstant();
        return render(ledger.openHistory(chatId, userId, updateId, period, from, until));
    }

    private boolean matchesCurrentBoundary(FundLedger.HistoryView view) {
        return view.period().equals("menu") || view.from().atZone(clock.getZone()).getHour() == dayStartHour
                && view.until().atZone(clock.getZone()).getHour() == dayStartHour;
    }

    private String render(FundLedger.HistoryView view) {
        var page = ledger.historyPage(view);
        String title = switch (view.period()) {
            case "today" -> "За сегодня";
            case "week" -> "На текущей неделе";
            default -> "За текущий месяц";
        };
        LocalDate first = view.from().atZone(clock.getZone()).toLocalDate();
        LocalDate last = view.until().atZone(clock.getZone()).toLocalDate().minusDays(1);
        StringBuilder out = new StringBuilder("📒 " + title + " · " + DATE.format(first)
                + (first.equals(last) ? "" : " — " + DATE.format(last))
                + "\nБарные дни с " + String.format(Locale.ROOT, "%02d:00", dayStartHour) + " · Минск."
                + "\nПо времени записи в журнал; ESUPL — по дате сверки."
                + "\nОпераций: " + page.total() + " · Страница " + (view.page() + 1) + "/" + page.pages() + "\n");
        if (page.entries().isEmpty()) out.append("\nЗа этот период операций нет.\n");
        for (var entry : page.entries()) {
            String description = entry.kind().equals("ESUPL")
                    ? (entry.cents() < 0 ? "Корректировка закрытого чека" : "Закрытый чек") : entry.note();
            out.append("\n#").append(entry.id()).append(" · ").append(DATE.format(entry.createdAt().atZone(clock.getZone())))
                    .append(" ").append(entry.cents() > 0 ? "+" : "").append(FundLedger.money(entry.cents()))
                    .append("\n").append(description).append("\n").append(entry.actor().name()).append("\n");
        }
        out.append("\nЧтобы увидеть новые записи, выбери период ещё раз.");
        return out.toString();
    }

    public ReplyKeyboardMarkup keyboardFor(long chatId, long userId) {
        var view = ledger.historyView(chatId, userId);
        if (view == null) return null;
        List<List<KeyboardButton>> rows = new ArrayList<>();
        if (!view.period().equals("menu") && matchesCurrentBoundary(view)) {
            var page = ledger.historyPage(view);
            List<KeyboardButton> navigation = new ArrayList<>();
            if (view.page() > 0) navigation.add(new KeyboardButton(PREVIOUS));
            if (view.page() + 1 < page.pages()) navigation.add(new KeyboardButton(NEXT));
            if (!navigation.isEmpty()) rows.add(navigation);
        }
        rows.add(List.of(new KeyboardButton(TODAY), new KeyboardButton(WEEK)));
        rows.add(List.of(new KeyboardButton(MONTH)));
        rows.add(List.of(new KeyboardButton(MENU)));
        return new ReplyKeyboardMarkup(rows, true, false, "Выбери период истории");
    }
}
