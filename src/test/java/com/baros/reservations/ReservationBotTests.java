package com.baros.reservations;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.*;
import java.util.*;

import static com.baros.reservations.ReservationStore.*;
import static org.assertj.core.api.Assertions.*;

class ReservationBotTests {
    @TempDir Path directory;
    ReservationSettings settings;
    ReservationStore store;
    ReservationBot bot;
    Clock clock = Clock.fixed(Instant.parse("2026-11-09T10:00:00Z"), ZoneOffset.UTC);
    long sequence;
    static final LocalDate DAY = LocalDate.of(2026, 11, 9);

    @BeforeEach void setup() throws Exception {
        settings = new ReservationSettings(directory.resolve("reservations.sqlite").toString(), Set.of(1L), -100L, true,
                18, 2, 120, Map.of("12", 5, "14", 2));
        store = new ReservationStore(settings);
        bot = new ReservationBot(settings, store, clock);
    }
    Map<String, Object> message(long update, long user, long chat, String type, String text) {
        return Map.of("update_id", update, "message", Map.of("chat", Map.of("id", chat, "type", type),
                "from", Map.of("id", user, "first_name", "Тест"), "text", text));
    }
    void text(long user, String text) { bot.process(message(++sequence, user, user, "private", text)); }
    Map<String, Object> callback(long update, long user, long chat, String type, String data) {
        return Map.of("update_id", update, "callback_query", Map.of("id", "c" + update,
                "from", Map.of("id", user), "data", data, "message", Map.of("chat", Map.of("id", chat, "type", type))));
    }
    void click(long user, String data) { bot.process(callback(++sequence, user, user, "private", data)); }
    long starts(LocalDate day, int hour, int minute) { return day.atTime(hour, minute).atZone(ReservationSettings.ZONE).toEpochSecond(); }
    long wizard(long user, boolean manual, LocalDate day, int hour, int minute, int party) {
        text(user, manual ? "Добавить бронь" : "Забронировать стол");
        long nonce = sequence;
        click(user, "d:" + nonce + ":" + day.toEpochDay());
        click(user, "t:" + nonce + ":" + starts(hour < 18 ? day.plusDays(1) : day, hour, minute));
        text(user, "" + party); text(user, "Тестовый Гость"); text(user, "+375 (29) 123-45-67"); text(user, "Нет");
        return nonce;
    }
    long book(long user, int hour, int minute, int party) {
        long nonce = wizard(user, false, DAY, hour, minute, party);
        click(user, "go:" + nonce);
        return number(store.transaction(c -> one(c, "SELECT * FROM reservations ORDER BY id DESC LIMIT 1")), "id");
    }
    Map<String, Object> booking(long id) { return store.transaction(c -> one(c, "SELECT * FROM reservations WHERE id=?", id)); }
    long count() { return number(store.transaction(c -> one(c, "SELECT COUNT(*) AS n FROM reservations")), "n"); }
    void clearMessages() { store.transaction(c -> execute(c, "DELETE FROM reservation_outbox")); }
    String messages() {
        return store.transaction(c -> rows(c, "SELECT body FROM reservation_outbox ORDER BY id")).toString();
    }

    @Test void guestRequestStaffConfirmationAndCancellationSurviveRestart() throws Exception {
        long id = book(101, 19, 30, 3);
        assertThat(booking(id)).containsEntry("status", "PENDING").containsEntry("phone", "+375291234567");
        click(1, "assign:" + id + ":1:12");
        assertThat(booking(id)).containsEntry("status", "CONFIRMED").containsEntry("table_id", "12");
        store = new ReservationStore(settings); bot = new ReservationBot(settings, store, clock);
        click(101, "cancel:" + id + ":2");
        assertThat(booking(id)).containsEntry("status", "CANCELLED");
        assertThat(messages()).contains("Подтверждена", "Отменена", "реальный стол не бронируется");
    }
    @Test void guestsCannotReadOtherBookingsOrInvokeStaffActions() {
        long id = book(101, 19, 0, 2);
        clearMessages();
        click(102, "view:" + id); click(102, "assign:" + id + ":1:12");
        click(102, "cancel:" + id + ":1"); text(102, "Новые заявки");
        assertThat(messages()).contains("Нет доступа", "только для сотрудников").doesNotContain("Тестовый Гость", "+375291234567");
        assertThat(booking(id)).containsEntry("status", "PENDING");
    }
    @Test void onlyAuthorizedStaffInConfiguredGroupCanConfirm() {
        long id = book(101, 19, 0, 2);
        bot.process(callback(++sequence, 102, -100, "supergroup", "assign:" + id + ":1:12"));
        bot.process(callback(++sequence, 1, -999, "supergroup", "assign:" + id + ":1:12"));
        assertThat(booking(id)).containsEntry("status", "PENDING");
        bot.process(callback(++sequence, 1, -100, "supergroup", "assign:" + id + ":1:12"));
        assertThat(booking(id)).containsEntry("status", "CONFIRMED");
    }
    @Test void staleTableChoiceCannotDoubleBookAndAdjacentBookingsAreAllowed() {
        long a = book(101, 19, 0, 3);
        long b = book(102, 20, 0, 3);
        click(1, "choose:" + b); // Table looked free when the staff member opened this panel.
        click(1, "assign:" + a + ":1:12");
        click(1, "assign:" + b + ":1:12");
        assertThat(booking(b)).containsEntry("status", "PENDING");
        assertThat(messages()).contains("Этот стол уже занят");
        long adjacent = book(103, 21, 0, 3);
        click(1, "assign:" + adjacent + ":1:12");
        assertThat(booking(adjacent)).containsEntry("status", "CONFIRMED");
    }
    @Test void capacityAndLateApprovalAreCheckedAtConfirmation() {
        long id = book(101, 19, 0, 3);
        click(1, "assign:" + id + ":1:14");
        assertThat(booking(id)).containsEntry("status", "PENDING");
        bot = new ReservationBot(settings, store, Clock.fixed(Instant.parse("2026-11-09T17:00:00Z"), ZoneOffset.UTC));
        click(1, "assign:" + id + ":1:12");
        assertThat(booking(id)).containsEntry("status", "PENDING");
    }
    @Test void afterMidnightBelongsToSelectedBarDayAndMustFinishBeforeClose() {
        long id = book(101, 0, 0, 2);
        assertThat(booking(id)).containsEntry("business_day", "2026-11-09");
        assertThat(number(booking(id), "starts")).isEqualTo(starts(DAY.plusDays(1), 0, 0));
        long nonce = wizard(102, false, DAY, 23, 0, 2);
        // Forged/stale callback cannot choose a slot ending after closing.
        text(103, "Забронировать стол"); long otherNonce = sequence;
        click(103, "d:" + otherNonce + ":" + DAY.toEpochDay());
        click(103, "t:" + otherNonce + ":" + starts(DAY.plusDays(1), 0, 30));
        assertThat(messages()).contains("Это время уже недоступно");
        assertThat(nonce).isPositive();
    }
    @Test void replayedUpdateAndRepeatedSubmitCreateOnlyOneBooking() {
        long nonce = wizard(101, false, DAY, 19, 0, 2);
        var update = callback(++sequence, 101, 101, "private", "go:" + nonce);
        bot.process(update); bot.process(update); click(101, "go:" + nonce);
        assertThat(count()).isEqualTo(1);
        assertThat(store.offset()).isEqualTo(sequence + 1);
    }
    @Test void oldWizardButtonsAndMalformedCallbackCannotCorruptCurrentDraft() {
        text(101, "Забронировать стол"); long old = sequence;
        text(101, "Забронировать стол"); long current = sequence;
        click(101, "d:" + old + ":" + DAY.toEpochDay());
        click(101, "d:" + current + ":9223372036854775807");
        click(101, "assign:garbage");
        click(101, "d:" + current + ":" + DAY.toEpochDay());
        assertThat(messages()).contains("Кнопка устарела", "Выбери время прибытия");
        assertThat(count()).isZero();
    }
    @Test void accessNeedsOwnerApprovalAndRevocationInvalidatesStaffButtonsAndDraft() {
        text(2, "/access"); click(101, "staff:add:2");
        var denied = store.transaction(c -> one(c, "SELECT * FROM reservation_staff WHERE user_id=2"));
        assertThat(denied).isNull();
        click(1, "staff:add:2");
        long nonce = wizard(2, true, DAY, 19, 0, 2);
        click(1, "staff:remove:2"); click(2, "go:" + nonce);
        assertThat(count()).isZero();
        long id = book(101, 19, 0, 2);
        click(2, "assign:" + id + ":1:12");
        assertThat(booking(id)).containsEntry("status", "PENDING");
    }
    @Test void manualBookingHasNoGuestIdAndIsConfirmedNormally() {
        long nonce = wizard(1, true, DAY, 19, 0, 2); click(1, "go:" + nonce);
        var b = store.transaction(c -> one(c, "SELECT * FROM reservations"));
        assertThat(b.get("guest_id")).isNull();
        click(1, "assign:" + number(b, "id") + ":1:12");
        assertThat(booking(number(b, "id"))).containsEntry("status", "CONFIRMED");
    }
    @Test void transferPreservesOriginalWhenNewTimeConflictsAndRejectsStaleCards() {
        long first = book(101, 19, 0, 3); click(1, "assign:" + first + ":1:12");
        long second = book(102, 21, 0, 3); click(1, "assign:" + second + ":1:12");
        click(1, "move:" + first); long nonce = sequence;
        click(1, "d:" + nonce + ":" + DAY.toEpochDay());
        click(1, "t:" + nonce + ":" + starts(DAY, 21, 0)); click(1, "go:" + nonce);
        assertThat(number(booking(first), "starts")).isEqualTo(starts(DAY, 19, 0));
        assertThat(booking(first)).containsEntry("status", "CONFIRMED");
        click(1, "move:" + first); nonce = sequence;
        click(1, "d:" + nonce + ":" + DAY.toEpochDay());
        click(1, "t:" + nonce + ":" + starts(DAY, 18, 0)); click(1, "go:" + nonce);
        assertThat(number(booking(first), "starts")).isEqualTo(starts(DAY, 18, 0));
        click(101, "cancel:" + first + ":2"); // old version predating the transfer
        assertThat(booking(first)).containsEntry("status", "CONFIRMED");
    }
    @Test void failedOutboxWriteRollsBackBookingAndOffsetSoSameUpdateCanRetry() {
        long nonce = wizard(101, false, DAY, 19, 0, 2);
        long before = store.offset();
        store.transaction(c -> execute(c, "CREATE TRIGGER fail_outbox BEFORE INSERT ON reservation_outbox WHEN NEW.method='sendMessage' BEGIN SELECT RAISE(ABORT,'test failure'); END"));
        var update = callback(++sequence, 101, 101, "private", "go:" + nonce);
        assertThatThrownBy(() -> bot.process(update)).isInstanceOf(IllegalStateException.class);
        assertThat(count()).isZero(); assertThat(store.offset()).isEqualTo(before);
        store.transaction(c -> execute(c, "DROP TRIGGER fail_outbox"));
        bot.process(update); assertThat(count()).isEqualTo(1);
    }
    @Test void draftSurvivesRestartAndRemindersAreQueuedOnlyOnce() throws Exception {
        long nonce = wizard(101, false, DAY, 19, 0, 2);
        store = new ReservationStore(settings); bot = new ReservationBot(settings, store, clock);
        click(101, "go:" + nonce); click(1, "assign:1:1:12");
        clearMessages();
        bot = new ReservationBot(settings, store, Clock.fixed(Instant.parse("2026-11-09T14:30:00Z"), ZoneOffset.UTC));
        bot.reminders(); bot.reminders();
        var queued = store.transaction(c -> rows(c, "SELECT * FROM reservation_outbox"));
        assertThat(queued).hasSize(1);
        assertThat(messages()).contains("Напоминаем о визите");
    }
    @Test void setupWithoutOwnerOnlyRevealsIdsAndCreatesNoBookings() throws Exception {
        var setup = new ReservationSettings(directory.resolve("setup.sqlite").toString(), Set.of(), 0, true, 18, 2, 120, Map.of("12", 5));
        store = new ReservationStore(setup); bot = new ReservationBot(setup, store, clock);
        text(101, "Забронировать стол");
        assertThat(count()).isZero(); assertThat(messages()).contains("Твой Telegram ID: 101");
    }
    @Test void sharedPhoneMustBelongToSenderAndExpiredDraftCanBeRestarted() {
        text(101, "Забронировать стол"); long nonce = sequence;
        click(101, "d:" + nonce + ":" + DAY.toEpochDay());
        click(101, "t:" + nonce + ":" + starts(DAY, 19, 0));
        text(101, "2"); text(101, "Тестовый Гость");
        bot.process(Map.of("update_id", ++sequence, "message", Map.of("chat", Map.of("id", 101, "type", "private"),
                "from", Map.of("id", 101), "contact", Map.of("user_id", 102, "phone_number", "375291234567"))));
        assertThat(messages()).contains("Поделись своим контактом");
        bot.process(Map.of("update_id", ++sequence, "message", Map.of("chat", Map.of("id", 101, "type", "private"),
                "from", Map.of("id", 101), "contact", Map.of("user_id", 101, "phone_number", "375291234567"))));
        text(101, "Нет"); click(101, "go:" + nonce);
        assertThat(count()).isEqualTo(1);
        text(102, "Забронировать стол");
        bot = new ReservationBot(settings, store, Clock.offset(clock, Duration.ofHours(1)));
        text(102, "2"); assertThat(messages()).contains("Время ввода истекло");
        text(102, "/start");
        assertThat(messages()).contains("Бар «Персонажи»");
    }
}
