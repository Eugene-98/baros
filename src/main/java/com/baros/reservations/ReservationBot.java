package com.baros.reservations;

import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static com.baros.reservations.ReservationStore.*;

@Service
@Profile("reservations")
public class ReservationBot {
    private final ReservationSettings settings;
    private final ReservationStore store;
    private final Clock clock;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    @Autowired
    public ReservationBot(ReservationSettings settings, ReservationStore store) {
        this(settings, store, Clock.systemUTC());
    }
    ReservationBot(ReservationSettings settings, ReservationStore store, Clock clock) {
        this.settings = settings; this.store = store; this.clock = clock;
    }
    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }
    static long id(Map<String, Object> map, String key) {
        return map.get(key) instanceof Number n ? n.longValue() : 0;
    }
    private long now() { return clock.instant().getEpochSecond(); }

    public void process(Map<String, Object> update) {
        if (!(update.get("update_id") instanceof Number)) throw new IllegalArgumentException("Missing update ID");
        long updateId = id(update, "update_id");
        store.transaction(c -> {
            var offset = one(c, "SELECT value FROM reservation_meta WHERE key='offset'");
            if (offset != null && updateId < number(offset, "value")) return null;
            Map<String, Object> callback = map(update.get("callback_query"));
            Map<String, Object> message = map(callback.isEmpty() ? update.get("message") : callback.get("message"));
            Map<String, Object> actor = map(callback.isEmpty() ? message.get("from") : callback.get("from"));
            Map<String, Object> chat = map(message.get("chat"));
            long user = id(actor, "id"), chatId = id(chat, "id");
            if (user > 0 && chatId != 0 && !Boolean.TRUE.equals(actor.get("is_bot"))) {
                if (!callback.isEmpty()) queue(c, "answerCallbackQuery", Map.of("callback_query_id", str(callback, "id")));
                try (Statement s = c.createStatement()) {
                    s.execute("SAVEPOINT operation");
                    try {
                        dispatch(c, updateId, user, chatId, "private".equals(str(chat, "type")), actor, message, callback);
                    } catch (Input e) {
                        s.execute("ROLLBACK TO operation");
                        send(c, chatId, e.getMessage(), null);
                    }
                    s.execute("RELEASE operation");
                }
            }
            execute(c, "INSERT INTO reservation_meta(key,value) VALUES ('offset',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", updateId + 1);
            return null;
        });
    }

    private void dispatch(Connection c, long updateId, long user, long chat, boolean privateChat,
                          Map<String, Object> actor, Map<String, Object> message, Map<String, Object> callback) throws Exception {
        String text = str(message, "text").trim();
        String command = text.split("\\s+", 2)[0].split("@", 2)[0];
        if (callback.isEmpty() && command.equals("/id")) {
            send(c, chat, "Твой Telegram ID: " + user + "\nID чата: " + chat, null); return;
        }
        if (settings.owners().isEmpty()) {
            if (privateChat) send(c, chat, "Настройка тестового бота. Твой Telegram ID: " + user
                    + "\nУкажи его в RESERVATION_OWNER_USER_IDS и перезапусти сервис.", null);
            return;
        }
        boolean staff = staff(c, user);
        if (!privateChat) {
            if (chat != settings.staffChat() || !staff || callback.isEmpty()) return;
        }
        if (!callback.isEmpty()) {
            callback(c, updateId, user, chat, privateChat, str(callback, "data")); return;
        }
        if (command.equals("/start") || command.equals("/cancel") || text.equals("В меню")) {
            execute(c, "DELETE FROM reservation_sessions WHERE user_id=?", user);
            send(c, chat, command.equals("/cancel") ? "Ввод отменён." : "Бар «Персонажи». Выбери действие.", menu(staff, settings.owners().contains(user)));
        } else if (command.equals("/access")) {
            requestAccess(c, user, chat, actor);
        } else if (command.equals("/staff") || text.equals("Сотрудники")) {
            requireOwner(user); showStaff(c, chat);
        } else if (text.equals("Забронировать стол") || text.equals("Добавить бронь") || command.equals("/book")) {
            boolean manual = text.equals("Добавить бронь");
            if (manual) requireStaff(c, user);
            if (!manual && rows(c, "SELECT id FROM reservations WHERE guest_id=? AND status IN ('PENDING','CONFIRMED') AND ends>?", user, now()).size() >= 3)
                throw new Input("У тебя уже три активные брони. Отмени ненужную или обратись к сотруднику.");
            Map<String, Object> draft = new LinkedHashMap<>();
            draft.put("nonce", updateId); draft.put("manual", manual); draft.put("step", "DATE");
            save(c, user, draft); dates(c, chat, "Выбери день визита. Бронь на " + settings.duration() + " мин.", "d:" + updateId + ":", 0);
        } else if (text.equals("Мои брони") || command.equals("/my")) {
            list(c, user, chat, "mine", 0, null);
        } else if (text.equals("Новые заявки") || command.equals("/pending")) {
            requireStaff(c, user); list(c, user, chat, "pending", 0, null);
        } else if (text.equals("Брони на дату") || command.equals("/day")) {
            requireStaff(c, user); dates(c, chat, "Выбери барный день для просмотра.", "day:", 0);
        } else if (command.equals("/help")) {
            send(c, chat, "Заявка станет бронью после подтверждения сотрудником. В «Мои брони» можно проверить статус и отменить бронь.\n"
                    + "/cancel — отменить текущий ввод\n/id — узнать свой ID\n/access — запросить доступ сотрудника", menu(staff, settings.owners().contains(user)));
        } else {
            input(c, updateId, user, chat, message);
        }
    }

    private void callback(Connection c, long updateId, long user, long chat, boolean privateChat, String data) throws Exception {
        String[] p = data.split(":");
        try {
            switch (p[0]) {
                case "staff" -> {
                    requireOwner(user);
                    long target = Long.parseLong(p[2]);
                    if (settings.owners().contains(target)) throw new Input("Владелец назначается в настройках сервиса.");
                    if (p[1].equals("add")) {
                        var request = one(c, "SELECT name FROM reservation_access_requests WHERE user_id=?", target);
                        if (request == null) throw new Input("Заявка на доступ уже обработана.");
                        execute(c, "INSERT OR REPLACE INTO reservation_staff VALUES (?,?)", target, str(request, "name"));
                        execute(c, "DELETE FROM reservation_access_requests WHERE user_id=?", target);
                        send(c, target, "Тебе открыт доступ сотрудника. Нажми /start.", null);
                    } else if (p[1].equals("deny")) {
                        execute(c, "DELETE FROM reservation_access_requests WHERE user_id=?", target);
                        send(c, target, "Запрос доступа отклонён.", null);
                    } else if (p[1].equals("remove")) {
                        execute(c, "DELETE FROM reservation_staff WHERE user_id=?", target);
                        execute(c, "DELETE FROM reservation_sessions WHERE user_id=?", target);
                    } else throw new Input("Неизвестное действие.");
                    audit(c, updateId, user, null, "staff:" + p[1] + ":" + target);
                    send(c, chat, "Права обновлены.", null);
                }
                case "view" -> card(c, user, chat, booking(c, Long.parseLong(p[1])));
                case "choose" -> {
                    requireStaff(c, user);
                    var b = booking(c, Long.parseLong(p[1]));
                    if (!str(b, "status").equals("PENDING")) throw new Input("Заявка уже обработана. Открой её заново.");
                    var buttons = new ArrayList<List<Map<String, String>>>();
                    for (var table : settings.tables().entrySet())
                        if (table.getValue() >= number(b, "party") && available(c, table.getKey(), number(b, "starts"), number(b, "ends"), number(b, "id")))
                            buttons.add(List.of(button("Стол " + table.getKey() + " · " + table.getValue() + " мест",
                                    "assign:" + number(b, "id") + ":" + number(b, "version") + ":" + table.getKey())));
                    send(c, chat, buttons.isEmpty() ? "Свободных подходящих столов нет. Можно перенести заявку или отклонить её."
                            : "Выбери стол для заявки №" + number(b, "id"), inline(buttons));
                }
                case "assign" -> {
                    requireStaff(c, user);
                    var b = booking(c, Long.parseLong(p[1]));
                    if (!str(b, "status").equals("PENDING") || number(b, "version") != Long.parseLong(p[2]))
                        throw new Input("Заявка изменилась. Открой её заново.");
                    Integer capacity = settings.tables().get(p[3]);
                    if (capacity == null || capacity < number(b, "party")) throw new Input("Стол не подходит по вместимости.");
                    settings.validateSlot(LocalDate.parse(str(b, "business_day")), number(b, "starts"), clock.instant());
                    if (!available(c, p[3], number(b, "starts"), number(b, "ends"), number(b, "id")))
                        throw new Input("Этот стол уже занят. Выбери другой.");
                    execute(c, "UPDATE reservations SET status='CONFIRMED',table_id=?,version=version+1 WHERE id=?", p[3], number(b, "id"));
                    audit(c, updateId, user, number(b, "id"), "confirmed:table:" + p[3]);
                    changed(c, user, chat, number(b, "id"), "Бронь подтверждена", "Подтвердил сотрудник ID " + user);
                }
                case "cancel", "reject" -> {
                    var b = booking(c, Long.parseLong(p[1]));
                    boolean employee = staff(c, user);
                    if (!employee && (!p[0].equals("cancel") || !Objects.equals(b.get("guest_id"), user)
                            && !(b.get("guest_id") instanceof Number n && n.longValue() == user)))
                        throw new Input("Нет доступа к этой брони.");
                    if (p[0].equals("reject")) requireStaff(c, user);
                    if (p.length != 3 || number(b, "version") != Long.parseLong(p[2])) throw new Input("Бронь изменилась. Открой её заново.");
                    if (!Set.of("PENDING", "CONFIRMED").contains(str(b, "status"))) throw new Input("Бронь уже закрыта.");
                    if (number(b, "ends") <= now()) throw new Input("Время этой брони уже прошло.");
                    if (p[0].equals("reject") && !str(b, "status").equals("PENDING")) throw new Input("Бронь уже подтверждена. Используй отмену.");
                    String status = p[0].equals("reject") ? "REJECTED" : "CANCELLED";
                    execute(c, "UPDATE reservations SET status=?,version=version+1 WHERE id=?", status, number(b, "id"));
                    audit(c, updateId, user, number(b, "id"), status);
                    changed(c, user, chat, number(b, "id"), status.equals("REJECTED") ? "Заявка отклонена" : "Бронь отменена",
                            "Действие выполнил ID " + user);
                }
                case "move" -> {
                    requirePrivate(privateChat); requireStaff(c, user);
                    var b = booking(c, Long.parseLong(p[1]));
                    if (!Set.of("PENDING", "CONFIRMED").contains(str(b, "status"))) throw new Input("Эта бронь уже закрыта.");
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("nonce", updateId); d.put("manual", true); d.put("step", "DATE");
                    d.put("booking", number(b, "id")); d.put("version", number(b, "version"));
                    d.put("name", str(b, "name")); d.put("phone", str(b, "phone")); d.put("note", str(b, "note")); d.put("party", number(b, "party"));
                    save(c, user, d); dates(c, chat, "Выбери новый день. Исходная бронь сохраняется до подтверждения переноса.", "d:" + updateId + ":", 0);
                }
                case "day" -> {
                    requireStaff(c, user); list(c, user, chat, "day", 0, LocalDate.ofEpochDay(Long.parseLong(p[1])));
                }
                case "page" -> {
                    LocalDate day = p.length > 3 ? LocalDate.ofEpochDay(Long.parseLong(p[3])) : null;
                    list(c, user, chat, p[1], Integer.parseInt(p[2]), day);
                }
                case "dates" -> {
                    requirePrivate(privateChat);
                    if (p[1].equals("day")) { requireStaff(c, user); dates(c, chat, "Выбери день.", "day:", Integer.parseInt(p[2])); }
                    else {
                        var d = draft(c, user, Long.parseLong(p[1]), "DATE");
                        dates(c, chat, "Выбери день визита.", "d:" + id(d, "nonce") + ":", Integer.parseInt(p[2]));
                    }
                }
                case "d", "t", "p", "go" -> {
                    requirePrivate(privateChat);
                    String expected = switch (p[0]) { case "d" -> "DATE"; case "t" -> "TIME"; case "p" -> "PARTY"; default -> "REVIEW"; };
                    var d = draft(c, user, Long.parseLong(p[1]), expected);
                    if (Boolean.TRUE.equals(d.get("manual"))) requireStaff(c, user);
                    if (p[0].equals("d")) {
                        LocalDate day = LocalDate.ofEpochDay(Long.parseLong(p[2]));
                        if (day.isBefore(settings.businessDay(clock.instant())) || day.isAfter(LocalDate.now(clock.withZone(ReservationSettings.ZONE)).plusDays(30)))
                            throw new Input("Выбери день из календаря.");
                        d.put("day", day.toString()); d.put("step", "TIME"); save(c, user, d); times(c, chat, d);
                    } else if (p[0].equals("t")) {
                        long starts = Long.parseLong(p[2]);
                        settings.validateSlot(LocalDate.parse(str(d, "day")), starts, clock.instant());
                        d.put("starts", starts);
                        if (d.containsKey("booking")) { d.put("step", "REVIEW"); save(c, user, d); review(c, chat, d); }
                        else {
                            d.put("step", "PARTY"); save(c, user, d);
                            var buttons = new ArrayList<List<Map<String, String>>>();
                            for (int i = 1; i <= 5; i++) buttons.add(List.of(button("" + i, "p:" + id(d, "nonce") + ":" + i)));
                            send(c, chat, "Сколько будет гостей? Нажми кнопку или введи число от 1 до 20. Большой компании потребуется согласование размещения.", inline(buttons));
                        }
                    } else if (p[0].equals("p")) {
                        party(c, user, chat, d, Integer.parseInt(p[2]));
                    } else submit(c, updateId, user, chat, d);
                }
                default -> throw new Input("Кнопка устарела. Нажми /start.");
            }
        } catch (NumberFormatException | IndexOutOfBoundsException | DateTimeException e) { throw new Input("Кнопка устарела. Нажми /start."); }
    }

    private void input(Connection c, long updateId, long user, long chat, Map<String, Object> message) throws Exception {
        var row = one(c, "SELECT value FROM reservation_sessions WHERE user_id=?", user);
        if (row == null) { send(c, chat, "Выбери действие в меню или нажми /start.", menu(staff(c, user), settings.owners().contains(user))); return; }
        var d = readDraft(row);
        if (now() - id(d, "updated") > 1800) { execute(c, "DELETE FROM reservation_sessions WHERE user_id=?", user); throw new Input("Время ввода истекло. Начни заново через /start."); }
        if (Boolean.TRUE.equals(d.get("manual"))) requireStaff(c, user);
        String text = str(message, "text").trim();
        switch (str(d, "step")) {
            case "PARTY" -> {
                try { party(c, user, chat, d, Integer.parseInt(text)); }
                catch (NumberFormatException e) { throw new Input("Введи количество гостей числом от 1 до 20."); }
            }
            case "NAME" -> {
                d.put("name", clean(text, 2, 60, "Введи имя: от 2 до 60 символов."));
                d.put("step", "PHONE"); save(c, user, d);
                Map<String, Object> keyboard = Boolean.TRUE.equals(d.get("manual")) ? Map.of("remove_keyboard", true)
                        : Map.of("keyboard", List.of(List.of(Map.of("text", "Поделиться телефоном", "request_contact", true))), "resize_keyboard", true, "one_time_keyboard", true);
                send(c, chat, "Оставь номер телефона с кодом страны, например +375291234567. Используем его для связи по брони.", keyboard);
            }
            case "PHONE" -> {
                var contact = map(message.get("contact"));
                if (!contact.isEmpty() && id(contact, "user_id") != user) throw new Input("Поделись своим контактом или введи номер текстом.");
                String phone = contact.isEmpty() ? text : str(contact, "phone_number");
                phone = phone.replaceAll("[\\s()\\-]", "");
                if (phone.matches("\\d{10,15}")) phone = "+" + phone;
                if (!phone.matches("\\+[1-9]\\d{9,14}")) throw new Input("Введи номер с кодом страны, например +375291234567.");
                d.put("phone", phone); d.put("step", "NOTE"); save(c, user, d);
                send(c, chat, "Есть пожелания? Напиши их или отправь «Нет». До 200 символов.", Map.of("keyboard", List.of(List.of(Map.of("text", "Нет"))), "resize_keyboard", true));
            }
            case "NOTE" -> {
                d.put("note", text.equalsIgnoreCase("нет") ? "" : clean(text, 1, 200, "Пожелания: до 200 символов."));
                d.put("step", "REVIEW"); save(c, user, d); review(c, chat, d);
            }
            default -> send(c, chat, "Продолжи с помощью кнопок выше. /cancel — отменить ввод.", null);
        }
    }
    private void party(Connection c, long user, long chat, Map<String, Object> d, int count) throws Exception {
        if (count < 1 || count > 20) throw new Input("Количество гостей: от 1 до 20.");
        d.put("party", count); d.put("step", "NAME"); save(c, user, d);
        send(c, chat, "На какое имя записать бронь?", Map.of("remove_keyboard", true));
    }
    private void submit(Connection c, long updateId, long user, long chat, Map<String, Object> d) throws Exception {
        LocalDate day = LocalDate.parse(str(d, "day"));
        long starts = id(d, "starts"), ends = starts + settings.duration() * 60L;
        settings.validateSlot(day, starts, clock.instant());
        long booking;
        if (d.containsKey("booking")) {
            requireStaff(c, user);
            var b = booking(c, id(d, "booking"));
            if (number(b, "version") != id(d, "version") || !Set.of("PENDING", "CONFIRMED").contains(str(b, "status")))
                throw new Input("Бронь уже изменена другим сотрудником. Открой её заново.");
            if (str(b, "status").equals("CONFIRMED")) {
                Integer capacity = settings.tables().get(str(b, "table_id"));
                if (capacity == null || capacity < number(b, "party")) throw new Input("Настройки стола изменились. Обратись к владельцу.");
                if (!available(c, str(b, "table_id"), starts, ends, number(b, "id")))
                    throw new Input("В новое время стол занят. Исходная бронь сохранена. Начни перенос заново.");
            }
            execute(c, "UPDATE reservations SET business_day=?, starts=?, ends=?,version=version+1,reminded=0 WHERE id=?", day.toString(), starts, ends, number(b, "id"));
            booking = number(b, "id");
            audit(c, updateId, user, booking, "rescheduled");
            changed(c, user, chat, booking, "Время брони изменено", "Перенёс сотрудник ID " + user);
        } else {
            boolean manual = Boolean.TRUE.equals(d.get("manual"));
            if (manual) requireStaff(c, user);
            if (!manual && rows(c, "SELECT id FROM reservations WHERE guest_id=? AND status IN ('PENDING','CONFIRMED') AND ends>?", user, now()).size() >= 3)
                throw new Input("Достигнут лимит: три активные брони.");
            execute(c, """
                    INSERT INTO reservations(guest_id,created_by,business_day,starts,ends,party,name,phone,note,status,created_at)
                    VALUES (?,?,?,?,?,?,?,?,?,'PENDING',?)
                    """, manual ? null : user, user, day.toString(), starts, ends, id(d, "party"), str(d, "name"), str(d, "phone"), str(d, "note"), now());
            booking = number(one(c, "SELECT last_insert_rowid() AS id"), "id");
            audit(c, updateId, user, booking, manual ? "manual-created" : "guest-created");
            var b = booking(c, booking);
            send(c, chat, manual ? "Заявка внесена. Выбери стол для подтверждения." : "Заявка отправлена. Дождись подтверждения сотрудника.", menu(staff(c, user), settings.owners().contains(user)));
            card(c, user, chat, b);
            notifyStaff(c, "Новая заявка\n" + details(b, true), staffButtons(b, false), b);
        }
        execute(c, "DELETE FROM reservation_sessions WHERE user_id=?", user);
    }

    private void dates(Connection c, long chat, String title, String prefix, int page) throws SQLException {
        if (page < 0 || page > 4) throw new Input("Неверная страница календаря.");
        LocalDate base = settings.businessDay(clock.instant());
        LocalDate last = LocalDate.now(clock.withZone(ReservationSettings.ZONE)).plusDays(30);
        List<List<Map<String, String>>> rows = new ArrayList<>();
        for (int i = page * 7; i < page * 7 + 7 && !base.plusDays(i).isAfter(last); i++) {
            LocalDate day = base.plusDays(i);
            rows.add(List.of(button(day.format(DATE), prefix + day.toEpochDay())));
        }
        String target = prefix.equals("day:") ? "day" : prefix.split(":")[1];
        var navigation = new ArrayList<Map<String, String>>();
        if (page > 0) navigation.add(button("Раньше", "dates:" + target + ":" + (page - 1)));
        if (base.plusDays(page * 7L + 7).isBefore(last.plusDays(1))) navigation.add(button("Позже", "dates:" + target + ":" + (page + 1)));
        if (!navigation.isEmpty()) rows.add(navigation);
        send(c, chat, title, inline(rows));
    }
    private void times(Connection c, long chat, Map<String, Object> d) throws SQLException {
        LocalDate day = LocalDate.parse(str(d, "day"));
        List<List<Map<String, String>>> buttons = new ArrayList<>();
        List<Map<String, String>> row = new ArrayList<>();
        for (ZonedDateTime t = settings.open(day); !t.plusMinutes(settings.duration()).isAfter(settings.close(day)); t = t.plusMinutes(30)) {
            if (t.toEpochSecond() <= now()) continue;
            row.add(button(t.format(TIME), "t:" + id(d, "nonce") + ":" + t.toEpochSecond()));
            if (row.size() == 3) { buttons.add(row); row = new ArrayList<>(); }
        }
        if (!row.isEmpty()) buttons.add(row);
        send(c, chat, buttons.isEmpty() ? "На этот день время закончилось. Нажми /cancel и выбери другой день."
                : "Выбери время прибытия. Время после полуночи указано с фактической датой.\nСвободный стол проверит сотрудник при подтверждении.", inline(buttons));
    }
    private void review(Connection c, long chat, Map<String, Object> d) throws SQLException {
        send(c, chat, "Проверь заявку:\n" + Instant.ofEpochSecond(id(d, "starts")).atZone(ReservationSettings.ZONE).format(TIME)
                + " · " + settings.duration() + " мин.\nГостей: " + id(d, "party") + "\n" + str(d, "name") + " · " + str(d, "phone")
                + (str(d, "note").isEmpty() ? "" : "\n" + str(d, "note")) + "\n/cancel — отменить ввод",
                inline(List.of(List.of(button(d.containsKey("booking") ? "Подтвердить перенос" : "Отправить заявку", "go:" + id(d, "nonce"))))));
    }

    private void list(Connection c, long user, long chat, String kind, int page, LocalDate day) throws SQLException {
        if (page < 0 || page > 1000) throw new Input("Неверная страница.");
        List<Map<String, Object>> found;
        if (kind.equals("mine")) found = rows(c, "SELECT * FROM reservations WHERE guest_id=? AND ends>? ORDER BY starts,id LIMIT 11 OFFSET ?", user, now(), page * 10);
        else {
            requireStaff(c, user);
            if (kind.equals("pending")) found = rows(c, "SELECT * FROM reservations WHERE status='PENDING' AND ends>? ORDER BY starts,id LIMIT 11 OFFSET ?", now(), page * 10);
            else if (kind.equals("day") && day != null) found = rows(c, "SELECT * FROM reservations WHERE business_day=? ORDER BY starts,id LIMIT 11 OFFSET ?", day.toString(), page * 10);
            else throw new Input("Неизвестный список.");
        }
        var buttons = new ArrayList<List<Map<String, String>>>();
        for (var b : found.subList(0, Math.min(10, found.size()))) buttons.add(List.of(button("№" + number(b, "id") + " · "
                + Instant.ofEpochSecond(number(b, "starts")).atZone(ReservationSettings.ZONE).format(TIME)
                + " · " + number(b, "party") + " чел. · " + status(str(b, "status")), "view:" + number(b, "id"))));
        var navigation = new ArrayList<Map<String, String>>();
        String suffix = day == null ? "" : ":" + day.toEpochDay();
        if (page > 0) navigation.add(button("Назад", "page:" + kind + ":" + (page - 1) + suffix));
        if (found.size() > 10) navigation.add(button("Дальше", "page:" + kind + ":" + (page + 1) + suffix));
        if (!navigation.isEmpty()) buttons.add(navigation);
        send(c, chat, found.isEmpty() ? "Броней нет." : "Брони" + (day == null ? "" : " на барный день " + day.format(DATE)) + ":", inline(buttons));
    }
    private Map<String, Object> booking(Connection c, long booking) throws SQLException {
        var b = one(c, "SELECT * FROM reservations WHERE id=?", booking);
        if (b == null) throw new Input("Бронь не найдена."); return b;
    }
    private void card(Connection c, long user, long chat, Map<String, Object> b) throws SQLException {
        boolean employee = staff(c, user);
        if (!employee && !(b.get("guest_id") instanceof Number n && n.longValue() == user)) throw new Input("Нет доступа к этой брони.");
        boolean active = Set.of("PENDING", "CONFIRMED").contains(str(b, "status")) && number(b, "ends") > now();
        Map<String, Object> keys = employee ? staffButtons(b, chat > 0) : active
                ? inline(List.of(List.of(button("Отменить бронь", "cancel:" + number(b, "id") + ":" + number(b, "version"))))) : null;
        send(c, chat, details(b, employee), keys);
        guardLastMessage(c, b, 0);
    }
    private Map<String, Object> staffButtons(Map<String, Object> b, boolean privateChat) {
        if (number(b, "ends") <= now() || !Set.of("PENDING", "CONFIRMED").contains(str(b, "status"))) return null;
        var buttons = new ArrayList<List<Map<String, String>>>();
        long booking = number(b, "id"), version = number(b, "version");
        if (str(b, "status").equals("PENDING")) buttons.add(List.of(button("Подтвердить · выбрать стол", "choose:" + booking), button("Отклонить", "reject:" + booking + ":" + version)));
        if (privateChat) buttons.add(List.of(button("Перенести", "move:" + booking)));
        buttons.add(List.of(button("Отменить", "cancel:" + booking + ":" + version)));
        return inline(buttons);
    }
    private void changed(Connection c, long actor, long chat, long booking, String title, String reason) throws SQLException {
        var b = booking(c, booking);
        send(c, chat, title + "\n" + details(b, staff(c, actor)), null);
        guardLastMessage(c, b, 0);
        if (b.get("guest_id") instanceof Number guest && guest.longValue() != chat) {
            send(c, guest.longValue(), title + "\n" + details(b, false), null);
            guardLastMessage(c, b, 0);
        }
        notifyStaff(c, title + "\n" + details(b, true) + "\n" + reason, staffButtons(b, false), b);
    }
    private String details(Map<String, Object> b, boolean employee) {
        String table = str(b, "table_id");
        return "№" + number(b, "id") + " · " + status(str(b, "status"))
                + "\nПрибытие: " + Instant.ofEpochSecond(number(b, "starts")).atZone(ReservationSettings.ZONE).format(TIME)
                + "\nДо: " + Instant.ofEpochSecond(number(b, "ends")).atZone(ReservationSettings.ZONE).format(TIME)
                + "\nГостей: " + number(b, "party") + (table.isEmpty() ? "" : " · Стол " + table)
                + "\n" + str(b, "name") + " · " + str(b, "phone")
                + (str(b, "note").isEmpty() ? "" : "\nПожелания: " + str(b, "note"))
                + (employee ? "\nИсточник: " + (b.get("guest_id") == null ? "сотрудник" : "гость") : "");
    }
    private static String status(String status) {
        return switch (status) { case "PENDING" -> "Ожидает подтверждения"; case "CONFIRMED" -> "Подтверждена";
            case "CANCELLED" -> "Отменена"; case "REJECTED" -> "Отклонена"; default -> status; };
    }
    private boolean staff(Connection c, long user) throws SQLException {
        return settings.owners().contains(user) || one(c, "SELECT user_id FROM reservation_staff WHERE user_id=?", user) != null;
    }
    private void requireStaff(Connection c, long user) throws SQLException { if (!staff(c, user)) throw new Input("Доступ только для сотрудников."); }
    private void requireOwner(long user) { if (!settings.owners().contains(user)) throw new Input("Доступ только для владельца."); }
    private void requirePrivate(boolean privateChat) { if (!privateChat) throw new Input("Продолжи в личном чате с ботом."); }
    private void requestAccess(Connection c, long user, long chat, Map<String, Object> actor) throws SQLException {
        if (staff(c, user)) { send(c, chat, "У тебя уже есть доступ сотрудника. Нажми /start.", null); return; }
        var old = one(c, "SELECT requested_at FROM reservation_access_requests WHERE user_id=?", user);
        if (old != null && now() - number(old, "requested_at") < 3600) throw new Input("Запрос уже отправлен. Дождись решения владельца.");
        String name = (str(actor, "first_name") + " " + str(actor, "last_name")).trim();
        name = name.substring(0, Math.min(name.length(), 80));
        execute(c, "INSERT OR REPLACE INTO reservation_access_requests VALUES (?,?,?)", user, name, now());
        var buttons = inline(List.of(List.of(button("Разрешить", "staff:add:" + user), button("Отклонить", "staff:deny:" + user))));
        for (long owner : settings.owners()) send(c, owner, "Запрос доступа сотрудника: " + name + "\nTelegram ID: " + user, buttons);
        send(c, chat, "Запрос отправлен владельцу.", null);
    }
    private void showStaff(Connection c, long chat) throws SQLException {
        var buttons = new ArrayList<List<Map<String, String>>>();
        for (var member : rows(c, "SELECT * FROM reservation_staff ORDER BY user_id"))
            buttons.add(List.of(button("Отозвать: " + str(member, "name") + " · " + number(member, "user_id"), "staff:remove:" + number(member, "user_id"))));
        send(c, chat, "Владельцы: " + settings.owners() + "\nЧтобы добавить сотрудника, попроси его отправить /access в личном чате с ботом.", inline(buttons));
    }
    private void notifyStaff(Connection c, String text, Map<String, Object> keyboard, Map<String, Object> booking) throws SQLException {
        if (settings.staffChat() != 0) { send(c, settings.staffChat(), text, keyboard); guardLastMessage(c, booking, 0); return; }
        Set<Long> recipients = new HashSet<>(settings.owners());
        for (var member : rows(c, "SELECT user_id FROM reservation_staff")) recipients.add(number(member, "user_id"));
        for (long recipient : recipients) { send(c, recipient, text, keyboard); guardLastMessage(c, booking, 0); }
    }
    private void save(Connection c, long user, Map<String, Object> draft) throws SQLException {
        draft.put("updated", now());
        execute(c, "INSERT OR REPLACE INTO reservation_sessions VALUES (?,?)", user, JSON.writeValueAsString(draft));
    }
    private Map<String, Object> readDraft(Map<String, Object> row) { return new LinkedHashMap<>(map(JSON.readValue(str(row, "value"), Map.class))); }
    private Map<String, Object> draft(Connection c, long user, long nonce, String step) throws SQLException {
        var row = one(c, "SELECT value FROM reservation_sessions WHERE user_id=?", user);
        if (row == null) throw new Input("Эта форма уже закрыта. Нажми /start.");
        var d = readDraft(row);
        if (id(d, "nonce") != nonce || !str(d, "step").equals(step) || now() - id(d, "updated") > 1800)
            throw new Input("Кнопка устарела. Продолжи текущую форму или нажми /cancel.");
        return d;
    }
    private void audit(Connection c, long updateId, long actor, Long booking, String action) throws SQLException {
        execute(c, "INSERT INTO reservation_audit(update_id,actor_id,booking_id,action,created_at) VALUES (?,?,?,?,?)", updateId, actor, booking, action, now());
    }
    private static String clean(String value, int min, int max, String error) {
        if (value.length() < min || value.length() > max || value.chars().anyMatch(ch -> Character.isISOControl(ch))) throw new Input(error);
        return value;
    }
    private void send(Connection c, long chat, String text, Map<String, Object> keyboard) throws SQLException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chat_id", chat); body.put("text", (settings.testMode() ? "🧪 Тест · реальный стол не бронируется\n\n" : "") + text);
        if (keyboard != null) body.put("reply_markup", keyboard);
        queue(c, "sendMessage", body);
    }
    private static Map<String, String> button(String text, String callback) { return Map.of("text", text, "callback_data", callback); }
    private static Map<String, Object> inline(List<List<Map<String, String>>> buttons) { return Map.of("inline_keyboard", buttons); }
    private static Map<String, Object> menu(boolean staff, boolean owner) {
        List<List<Map<String, String>>> rows = new ArrayList<>();
        rows.add(List.of(Map.of("text", "Забронировать стол"), Map.of("text", "Мои брони")));
        if (staff) {
            rows.add(List.of(Map.of("text", "Новые заявки"), Map.of("text", "Брони на дату")));
            rows.add(List.of(Map.of("text", "Добавить бронь")));
        }
        if (owner) rows.add(List.of(Map.of("text", "Сотрудники")));
        return Map.of("keyboard", rows, "resize_keyboard", true);
    }

    public void reminders() {
        store.transaction(c -> {
            for (var b : rows(c, "SELECT * FROM reservations WHERE status='CONFIRMED' AND guest_id IS NOT NULL AND reminded=0 AND starts>? AND starts<=?", now(), now() + 7200)) {
                send(c, number(b, "guest_id"), "Напоминаем о визите в «Персонажи».\n" + details(b, false), null);
                guardLastMessage(c, b, number(b, "starts"));
                execute(c, "UPDATE reservations SET reminded=1 WHERE id=?", number(b, "id"));
            }
            execute(c, "DELETE FROM reservation_sessions WHERE json_extract(value,'$.updated')<?", now() - 1800);
            return null;
        });
    }
}
