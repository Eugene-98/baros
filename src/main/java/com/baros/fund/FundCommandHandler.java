package com.baros.fund;

import com.baros.telegram.TelegramClient.*;
import com.baros.telegram.TelegramUpdateResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Profile("dvoyka")
public class FundCommandHandler {
    private static final Logger log = LoggerFactory.getLogger(FundCommandHandler.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private final FundLedger ledger;
    private final FundSyncService sync;
    private final Set<Long> allowedUsers;
    private final Set<Long> allowedChats;
    private final ZoneId zone;

    public FundCommandHandler(FundLedger ledger, FundSyncService sync,
                               @Value("${fund.allowed-user-ids:}") String users,
                               @Value("${fund.allowed-chat-ids:}") String chats,
                               @Value("${bar.time-zone}") String zone) {
        this.ledger = ledger;
        this.sync = sync;
        allowedUsers = ids(users);
        allowedChats = ids(chats);
        this.zone = ZoneId.of(zone);
    }

    public String handle(TelegramUpdateResponse.Update update) {
        var message = update.message();
        var user = message.from();
        String text = message.text() == null ? "" : message.text().trim();
        String command = text.split("\\s+", 2)[0].split("@", 2)[0].toLowerCase(Locale.ROOT);
        if (command.equals("/id")) {
            return "ID чата: " + message.chat().id() + "\nТвой Telegram ID: " + (user == null ? "не определён" : user.id());
        }
        if (user == null || user.id() == null || user.id() <= 0 || user.bot()) {
            return "Отправь команду от своего личного Telegram-аккаунта: для операций нужно сохранить автора.";
        }
        boolean privateChat = "private".equals(message.chat().type());
        // An allowed user in an unrelated group must not disclose the fund to that group.
        if (!(privateChat ? allowedUsers.contains(user.id()) || allowedChats.contains(message.chat().id())
                : allowedChats.contains(message.chat().id()))) {
            return "Доступ к «Двойке» ещё не настроен для этого чата. Команда /id покажет ID для настройки доступа.";
        }
        if (update.updateId() == null) throw new IllegalStateException("Telegram update has no ID");
        String name = (user.firstName() == null ? "" : user.firstName())
                + (user.lastName() == null ? "" : " " + user.lastName())
                + (user.username() == null ? "" : " @" + user.username());
        FundLedger.Actor actor = new FundLedger.Actor(user.id(), name);
        try {
            if (text.equals("💰 Остаток") || command.equals("/balance")) return balance();
            if (text.equals("📒 История") || command.equals("/history")) return history();
            if (text.equals("🔄 Синхронизировать") || command.equals("/sync")) {
                try {
                    var result = sync.sync(actor);
                    return "Синхронизация завершена.\nИзменённых чеков: " + result.changes()
                            + "\nИзменение: " + FundLedger.money(result.delta())
                            + "\nОстаток: " + FundLedger.money(result.balance());
                } catch (FundLedger.InputException e) {
                    throw e;
                } catch (RuntimeException e) {
                    log.error("Manual fund sync failed ({})", e.getClass().getSimpleName());
                    return "Синхронизация не завершена. Проверь время последней успешной сверки кнопкой «Остаток» и попробуй позже. "
                            + "Не добавляй сумму ESUPL вручную: при следующей сверке она будет учтена автоматически.";
                }
            }
            if (text.equals("➕ Поступление")) return "Отправь сумму и пояснение:\n/add 50 Возврат подотчёта\n\nИли коротко: + 50 Возврат подотчёта";
            if (text.equals("➖ Расход")) return "Отправь сумму и пояснение:\n/spend 25,50 Закупка лимонов\n\nИли коротко: - 25,50 Закупка лимонов";
            String[] parts = text.split("\\s+", 3);
            if (command.equals("/opening")) {
                if (parts.length != 3) return openingHelp();
                long cents = FundLedger.parseMoney(parts[1], true);
                LocalDate date = parseDate(parts[2]);
                if (date.isAfter(sync.currentBusinessDate())) throw new FundLedger.InputException("Дата отсчёта не может быть позже текущего барного дня.");
                long balance = ledger.opening(update.updateId(), cents, date, actor);
                return "Начальный остаток сохранён.\nОстаток: " + FundLedger.money(balance)
                        + "\nПоступления ESUPL учитываются с " + DATE.format(date) + " в 03:00.\nНажми «Синхронизировать» для загрузки завершённых дней.";
            }
            if (Set.of("/add", "/spend", "+", "-").contains(command)) {
                if (parts.length != 3) throw new FundLedger.InputException("Нужны сумма и пояснение. Например: /spend 25,50 Закупка лимонов");
                long cents = FundLedger.parseMoney(parts[1], false);
                boolean expense = command.equals("/spend") || command.equals("-");
                long balance = ledger.manual(update.updateId(), expense ? -cents : cents, parts[2], actor);
                return (expense ? "Расход" : "Поступление") + " записан: " + FundLedger.money(cents)
                        + "\n" + FundLedger.clean(parts[2], 140) + "\nОстаток: " + FundLedger.money(balance);
            }
            return help();
        } catch (FundLedger.InputException e) {
            return e.getMessage();
        } catch (DateTimeException e) {
            return "Не понял дату. Используй ДД.ММ.ГГГГ или ГГГГ-ММ-ДД и существующую дату.";
        }
    }

    private String balance() {
        var state = ledger.snapshot();
        if (state.balance() == null) return openingHelp();
        return "💰 Двойка: " + FundLedger.money(state.balance())
                + "\nДата отсчёта: " + DATE.format(state.startDate())
                + (state.lastSync() == null ? "\nESUPL ещё не синхронизирован."
                : "\nПоследняя синхронизация: " + TIME.format(Instant.parse(state.lastSync()).atZone(zone))
                + "\nESUPL учтён по барный день: " + DATE.format(LocalDate.parse(state.throughDate())))
                + "\nТекущая незавершённая смена в сумму ESUPL не входит.";
    }

    private String history() {
        List<FundLedger.Entry> entries = ledger.history();
        if (entries.isEmpty()) return "Операций пока нет.\n\n" + openingHelp();
        StringBuilder out = new StringBuilder("📒 Последние 10 операций\n");
        for (var entry : entries) {
            out.append("\n#").append(entry.id()).append(" · ").append(TIME.format(entry.createdAt().atZone(zone)))
                    .append(" · ").append(entry.cents() > 0 ? "+" : "").append(FundLedger.money(entry.cents()))
                    .append("\n").append(entry.note())
                    .append("\n").append(entry.actor().name()).append(" [").append(entry.actor().id()).append("]\n");
        }
        return out.toString();
    }

    private String help() {
        return "Двойка · общий фонд\n\n/balance — остаток\n/add 50 Пояснение — поступление\n/spend 25,50 Пояснение — расход\n/history — последние 10 операций\n/sync — сверка завершённых смен с ESUPL\n/id — ID для настройки доступа\n\n" + openingHelp();
    }

    private String openingHelp() {
        return "Первый запуск: /opening 1000 " + DATE.format(sync.currentBusinessDate())
                + "\nЭто остаток перед началом барного дня указанной даты (03:00). "
                + "Скидки ESUPL с этой даты будут прибавляться отдельно. Выбери дату так, чтобы эти суммы ещё не входили в начальный остаток. "
                + "Начальный остаток задаётся один раз; исправления — через поступление или расход с пояснением.";
    }

    private LocalDate parseDate(String text) {
        return text.contains(".") ? LocalDate.parse(text, DATE) : LocalDate.parse(text);
    }

    private static Set<Long> ids(String value) {
        if (value.isBlank()) return Set.of();
        return Arrays.stream(value.split(",")).map(String::trim).map(Long::parseLong).collect(Collectors.toUnmodifiableSet());
    }

    public static ReplyKeyboardMarkup keyboard() {
        return new ReplyKeyboardMarkup(List.of(
                List.of(new KeyboardButton("💰 Остаток"), new KeyboardButton("📒 История")),
                List.of(new KeyboardButton("➕ Поступление"), new KeyboardButton("➖ Расход")),
                List.of(new KeyboardButton("🔄 Синхронизировать"), new KeyboardButton("ℹ️ Помощь"))),
                true, false, "Выбери действие");
    }
}
