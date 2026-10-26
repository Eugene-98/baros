package com.baros.fund;

import com.baros.telegram.TelegramClient.*;
import com.baros.telegram.TelegramUpdateResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
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
    private static final List<String> EXPENSE_TEMPLATES = List.of("Хозяюшка", "Рынок", "Бар Закупка");
    private static final String CANCEL = "❌ Отмена";
    private static final String CUSTOM = "✍️ Своё назначение";
    private final FundLedger ledger;
    private final FundSyncService sync;
    private final Set<Long> allowedUsers;
    private final Set<Long> allowedChats;
    private final ZoneId zone;
    private final FundHistoryService history;

    @Autowired
    public FundCommandHandler(FundLedger ledger, FundSyncService sync,
                               @Value("${fund.allowed-user-ids:}") String users,
                               @Value("${fund.allowed-chat-ids:}") String chats,
                               @Value("${bar.time-zone}") String zone, FundHistoryService history) {
        this.ledger = ledger;
        this.sync = sync;
        allowedUsers = ids(users);
        allowedChats = ids(chats);
        this.zone = ZoneId.of(zone);
        this.history = history;
    }

    FundCommandHandler(FundLedger ledger, FundSyncService sync, String users, String chats, String zone) {
        this(ledger, sync, users, chats, zone, new FundHistoryService(ledger, zone, 3));
    }

    public String handle(TelegramUpdateResponse.Update update) {
        var message = update.message();
        var user = message.from();
        String text = message.text() == null ? "" : message.text().replaceAll("[\\p{Zs}\\s]+", " ").trim();
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
        long chatId = message.chat().id();
        try {
            // A completed guided input has already cleared its draft. Replay its saved receipt
            // before interpreting the text, including after a process restart.
            var saved = ledger.manualEntry(update.updateId());
            if (saved != null) return receipt(saved.cents(), saved.note(), ledger.snapshot().balance());
            String historyAnswer = history.handle(update.updateId(), chatId, user.id(), text);
            if (historyAnswer != null) return historyAnswer;
            ledger.closeHistory(chatId, user.id());
            if (text.equals(FundHistoryService.MENU)) {
                ledger.cancelDraft(chatId, user.id());
                return "Главное меню · выбери действие.";
            }
            if (text.equals(CANCEL) || command.equals("/cancel")) {
                ledger.cancelDraft(chatId, user.id());
                return "Ввод отменён. Новая операция не записана.";
            }
            if (Set.of("💰 Остаток", "📒 История", "🔄 Синхронизировать", "ℹ️ Помощь").contains(text)
                    || Set.of("/balance", "/history", "/sync", "/help", "/start").contains(command)) {
                ledger.cancelDraft(chatId, user.id());
            }
            if (text.equals("💰 Остаток") || command.equals("/balance")) return balance();
            if (text.equals("ℹ️ Помощь") || command.equals("/help") || command.equals("/start")) return help();
            if (text.equals("🔄 Синхронизировать") || command.equals("/sync")) {
                try {
                    var result = sync.sync(actor);
                    return "Синхронизация завершена.\nИзменённых чеков: " + result.changes()
                            + "\nИзменение: " + FundLedger.money(result.delta())
                            + "\nОстаток: " + FundLedger.money(result.balance());
                } catch (FundLedger.InputException e) {
                    throw e;
                } catch (RuntimeException e) {
                    String diagnostic = FundSyncService.failureDescription(e);
                    log.error("Manual fund sync failed: {}", diagnostic);
                    return "Синхронизация не завершена.\n" + diagnostic
                            + "\n\nПроверь время последней успешной сверки кнопкой «Остаток». При повторении ошибки пришли это сообщение разработчику. "
                            + "Не добавляй сумму ESUPL вручную: при следующей сверке она будет учтена автоматически.";
                }
            }
            if (text.equals("➕ Поступление") || (command.equals("/add") && !text.contains(" "))) {
                ledger.saveDraft(chatId, user.id(), new FundLedger.Draft(false, null));
                return "Отправь сумму и назначение одним сообщением:\n50 Возврат подотчёта\n\nСумма: 20, 20.00 или 20,00. Для выхода — «Отмена».";
            }
            if (text.equals("➖ Расход") || (command.equals("/spend") && !text.contains(" "))) {
                return startExpense(chatId, user.id());
            }
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
            if (command.equals("/add") || command.equals("/spend")) {
                return record(update.updateId(), chatId, actor, command.equals("/spend"),
                        text.substring(text.indexOf(' ') + 1), null);
            }
            if (text.startsWith("-") || text.startsWith("+") || text.startsWith("−")) {
                return record(update.updateId(), chatId, actor, !text.startsWith("+"), text.substring(1).trim(), null);
            }
            var draft = ledger.draft(chatId, user.id());
            if (draft != null && !text.startsWith("/")) {
                if (draft.expense() && text.equals(CUSTOM)) return startExpense(chatId, user.id());
                if (draft.expense() && EXPENSE_TEMPLATES.contains(text)) {
                    ledger.saveDraft(chatId, user.id(), new FundLedger.Draft(true, text));
                    return "Расход: «" + text + "».\nОтправь только сумму: 20, 20.00 или 20,00.\nДля выхода — «Отмена».";
                }
                return record(update.updateId(), chatId, actor, draft.expense(), text, draft.note());
            }
            return help();
        } catch (FundLedger.InputException e) {
            return e.getMessage();
        } catch (DateTimeException e) {
            return "Не понял дату. Используй ДД.ММ.ГГГГ или ГГГГ-ММ-ДД и существующую дату.";
        }
    }

    private String startExpense(long chatId, long userId) {
        ledger.saveDraft(chatId, userId, new FundLedger.Draft(true, null));
        return "Отправь сумму и назначение одним сообщением:\n20,50 Закупка лимонов\n\n"
                + "Или выбери шаблон ниже и отправь только сумму.\nСумма: 20, 20.00 или 20,00. Для выхода — «Отмена».";
    }

    private String record(long updateId, long chatId, FundLedger.Actor actor, boolean expense, String input, String template) {
        String[] values = input.split("\\s+", 2);
        if (template == null && values.length != 2) {
            throw new FundLedger.InputException("Нужны сумма и пояснение. Например: 20,50 Закупка лимонов (после кнопки) или -20,50 Закупка лимонов.");
        }
        if (template != null && values.length != 1) {
            throw new FundLedger.InputException("Для выбранного шаблона отправь только сумму: 20, 20.00 или 20,00. Для другого назначения нажми «Своё назначение».");
        }
        long cents = FundLedger.parseMoney(values[0], false);
        String note = template == null ? values[1] : template;
        long signed = expense ? -cents : cents;
        long balance = ledger.manual(updateId, signed, note, actor, chatId);
        return receipt(signed, note, balance);
    }

    private String receipt(long cents, String note, long balance) {
        return (cents < 0 ? "Расход записан: " : "Поступление записано: ") + FundLedger.money(Math.abs(cents))
                + "\n" + FundLedger.clean(note, 140) + "\nОстаток: " + FundLedger.money(balance);
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

    private String help() {
        return "Двойка · общий фонд\n\nРасход: -20 Назначение (можно - 20).\n"
                + "Или нажми «Расход» → отправь сумму и назначение.\n"
                + "Или «Расход» → выбери шаблон → отправь только сумму.\n"
                + "Сумма: 20, 20.00, 20,00; также 20.5 или 20,5.\n"
                + "Поступление: +50 Назначение или кнопка «Поступление».\n"
                + "/cancel — отменить ввод\n/balance — остаток\n/add 50 Пояснение — поступление\n/spend 25,50 Пояснение — расход\n/history — история за сегодня, неделю или месяц\n/sync — сверка завершённых смен с ESUPL\n/id — ID для настройки доступа\n\n" + openingHelp();
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

    public ReplyKeyboardMarkup keyboardFor(TelegramUpdateResponse.Update update) {
        var message = update.message();
        var user = message.from();
        if (user == null || user.id() == null || user.bot()) return keyboard();
        var historyKeyboard = history.keyboardFor(message.chat().id(), user.id());
        if (historyKeyboard != null) return historyKeyboard;
        var draft = ledger.draft(message.chat().id(), user.id());
        if (draft == null) return keyboard();
        var rows = new ArrayList<List<KeyboardButton>>();
        if (draft.expense()) {
            for (String template : EXPENSE_TEMPLATES) rows.add(List.of(new KeyboardButton(template)));
            if (draft.note() != null) rows.add(List.of(new KeyboardButton(CUSTOM)));
        }
        rows.add(List.of(new KeyboardButton(CANCEL)));
        return new ReplyKeyboardMarkup(rows, true, false, draft.note() == null ? "Сумма и назначение" : "Только сумма");
    }
}
