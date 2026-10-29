package com.baros.fund;

import com.baros.telegram.TelegramClient;
import com.baros.telegram.TelegramUpdateResponse.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FundHistoryTests {
    @TempDir Path temp;
    private static final ZoneId ZONE = ZoneId.of("Europe/Minsk");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-28T12:00:00Z"), ZONE);
    private FundLedger ledger;
    private FundHistoryService history;
    private String filename;

    @BeforeEach void setup() throws Exception {
        filename = temp.resolve("history.sqlite").toString();
        ledger = new FundLedger(filename);
        history = new FundHistoryService(ledger, CLOCK, 3);
    }

    private void seed(Instant time, String note) throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + filename);
             var p = c.prepareStatement("INSERT INTO fund_entries(cents,kind,note,actor_id,actor_name,created_at,source_key) VALUES (?,?,?,?,?,?,?)")) {
            p.setLong(1, -2050); p.setString(2, "EXPENSE"); p.setString(3, note);
            p.setLong(4, 123); p.setString(5, "Евгений @eugene"); p.setString(6, time.toString());
            p.setString(7, "fixture:" + UUID.randomUUID()); p.executeUpdate();
        }
    }

    private String handle(long id, String text) { return history.handle(id, 123, 123, text); }
    private List<Long> currentIds() {
        return ledger.historyPage(ledger.historyView(123, 123)).entries().stream().map(FundLedger.Entry::id).toList();
    }
    private List<String> buttons() {
        return history.keyboardFor(123, 123).keyboard().stream().flatMap(List::stream).map(TelegramClient.KeyboardButton::text).toList();
    }

    @ParameterizedTest
    @CsvSource({"today,2026-10-28T00:00:00Z", "week,2026-10-26T00:00:00Z", "month,2026-10-01T00:00:00Z"})
    void periodsUseMinskBusinessDaysAndKeepFractionalBoundaryEntries(String period, String fromText) throws Exception {
        Instant from = Instant.parse(fromText);
        Instant until = Instant.parse("2026-10-29T00:00:00Z");
        seed(from.minusNanos(1), "До периода");
        seed(from, "Начало");
        seed(from.plusMillis(100), "Первая секунда");
        seed(until.minusNanos(1), "Последняя секунда");
        seed(until, "Следующий день");
        String answer = handle(1, "/history " + period);
        assertThat(currentIds()).containsExactly(4L, 3L, 2L);
        assertThat(answer).contains("Операций: 3", "Евгений @eugene", "-20,50 BYN")
                .doesNotContain("До периода", "Следующий день");
        assertThat(ledger.historyView(123, 123).from()).isEqualTo(from);
    }

    @Test void beforeThreeUsesPreviousBusinessDayWeekAndMonth() {
        var beforeMonth = new FundHistoryService(ledger, Clock.fixed(Instant.parse("2026-10-31T23:59:59Z"), ZONE), 3);
        beforeMonth.handle(1, 123, 123, "/history month");
        assertThat(ledger.historyView(123, 123).from()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        var beforeMonday = new FundHistoryService(ledger, Clock.fixed(Instant.parse("2026-11-01T23:59:59Z"), ZONE), 3);
        beforeMonday.handle(2, 123, 123, "/history week");
        assertThat(ledger.historyView(123, 123).from()).isEqualTo(Instant.parse("2026-10-26T00:00:00Z"));
        var monday = new FundHistoryService(ledger, Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"), ZONE), 3);
        monday.handle(3, 123, 123, "/history week");
        assertThat(ledger.historyView(123, 123).from()).isEqualTo(Instant.parse("2026-11-02T00:00:00Z"));
        monday.handle(4, 123, 123, "/history month");
        assertThat(ledger.historyView(123, 123).from()).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
    }

    @Test void emptyHistoryHasPeriodMenuAndNoPageNavigation() {
        assertThat(handle(1, "📒 История")).contains("выбери период");
        assertThat(buttons()).contains(FundHistoryService.TODAY, FundHistoryService.WEEK, FundHistoryService.MONTH, FundHistoryService.MENU);
        assertThat(handle(2, FundHistoryService.MONTH)).contains("операций нет", "Страница 1/1");
        assertThat(buttons()).doesNotContain(FundHistoryService.NEXT, FundHistoryService.PREVIOUS);
    }

    @Test void paginationIsCompleteStableAndIndependentAcrossUsersAndRestart() throws Exception {
        for (int i = 1; i <= 25; i++) seed(CLOCK.instant(), "Запись " + i);
        handle(1, FundHistoryService.MONTH);
        List<Long> ids = new ArrayList<>(currentIds());
        assertThat(buttons()).contains(FundHistoryService.NEXT).doesNotContain(FundHistoryService.PREVIOUS);
        seed(CLOCK.instant(), "Новая запись");
        String pageTwo = handle(2, FundHistoryService.NEXT);
        ids.addAll(currentIds());
        ledger = new FundLedger(filename);
        history = new FundHistoryService(ledger, CLOCK, 3);
        assertThat(handle(2, FundHistoryService.NEXT)).isEqualTo(pageTwo);
        history.handle(3, 123, 456, FundHistoryService.WEEK);
        history.handle(4, -1001, 123, FundHistoryService.TODAY);
        assertThat(ledger.historyView(123, 123).page()).isEqualTo(1);
        handle(5, FundHistoryService.NEXT); ids.addAll(currentIds());
        handle(6, FundHistoryService.NEXT); ids.addAll(currentIds());
        assertThat(ids).hasSize(25).doesNotHaveDuplicates().contains(1L, 25L).doesNotContain(26L);
        assertThat(buttons()).contains(FundHistoryService.PREVIOUS).doesNotContain(FundHistoryService.NEXT);
        assertThat(handle(7, FundHistoryService.PREVIOUS)).contains("Страница 3/4");
        assertThat(handle(8, FundHistoryService.MONTH)).contains("Операций: 26", "Новая запись");
    }

    @Test void fullPageWithLongestStoredNotesAndNamesFitsTelegram() throws Exception {
        for (int i = 0; i < FundLedger.HISTORY_PAGE_SIZE; i++) seed(CLOCK.instant(), "Я".repeat(200));
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + filename); var p = c.prepareStatement("UPDATE fund_entries SET actor_name=?,actor_id=?,cents=?")) {
            p.setString(1, "Я".repeat(80)); p.setLong(2, Long.MAX_VALUE); p.setLong(3, Long.MIN_VALUE); p.executeUpdate();
        }
        assertThat(handle(1, FundHistoryService.MONTH).length()).isLessThanOrEqualTo(4096);
    }

    private Update update(long id, long userId, long chatId, String type, String text) {
        return new Update(id, new Message(new Chat(chatId, type), text, new User(userId, "Автор", null, null, false)));
    }

    @Test void commandAccessMenuAndDraftCancellationKeepMoneyUnchanged() {
        ledger.opening(1, 100_000, LocalDate.of(2026, 10, 1), new FundLedger.Actor(123, "Автор"));
        ledger.saveDraft(123, 123, new FundLedger.Draft(true, "Рынок"));
        var commands = new FundCommandHandler(ledger, mock(FundSyncService.class), "123", "-1001", "Europe/Minsk", history);
        assertThat(commands.handle(update(2, 456, 456, "private", FundHistoryService.MONTH))).contains("Доступ");
        assertThat(commands.handle(update(3, 123, -1002, "supergroup", "/history week"))).contains("Доступ");
        assertThat(ledger.historyView(456, 456)).isNull();
        assertThat(commands.handle(update(4, 123, 123, "private", "📒 История"))).contains("выбери период");
        assertThat(ledger.draft(123, 123)).isNull();
        var back = update(5, 123, 123, "private", FundHistoryService.MENU);
        assertThat(commands.handle(back)).contains("Главное меню");
        assertThat(commands.keyboardFor(back)).isEqualTo(FundCommandHandler.keyboard());
        assertThat(ledger.snapshot().balance()).isEqualTo(100_000);
    }

    @Test void failedTelegramReplyDoesNotSkipPageAfterRestart() throws Exception {
        for (int i = 0; i < 25; i++) seed(CLOCK.instant(), "Запись " + i);
        handle(1, FundHistoryService.MONTH);
        var telegram = mock(TelegramClient.class);
        var sync = mock(FundSyncService.class);
        var update = update(2, 123, 123, "private", FundHistoryService.NEXT);
        when(telegram.getUpdates(0)).thenReturn(List.of(update));
        doThrow(new IllegalStateException("Telegram unavailable")).doNothing().when(telegram).sendMessage(anyLong(), anyString(), any());
        var commands = new FundCommandHandler(ledger, sync, "123", "", "Europe/Minsk", history);
        assertThatThrownBy(() -> new FundPollingService(telegram, commands, ledger).pollOnce()).isInstanceOf(IllegalStateException.class);
        var restarted = new FundLedger(filename);
        var restartedHistory = new FundHistoryService(restarted, CLOCK, 3);
        var restartedCommands = new FundCommandHandler(restarted, sync, "123", "", "Europe/Minsk", restartedHistory);
        new FundPollingService(telegram, restartedCommands, restarted).pollOnce();
        assertThat(restarted.historyView(123, 123).page()).isEqualTo(1);
        assertThat(restarted.offset()).isEqualTo(3);
        verify(telegram, times(2)).sendMessage(eq(123L), contains("Страница 2/4"), any());
    }

    @Test void eightOclockHistoryExcludesPreviousShiftAndRefreshesOldViews() throws Exception {
        seed(Instant.parse("2026-10-28T04:59:59.999Z"), "До восьми");
        seed(Instant.parse("2026-10-28T05:00:00Z"), "После восьми");
        handle(1, FundHistoryService.TODAY); // Saved under the previous 03:00 boundary.
        history = new FundHistoryService(ledger, CLOCK, 8);
        assertThat(buttons()).doesNotContain(FundHistoryService.NEXT, FundHistoryService.PREVIOUS);
        assertThat(handle(2, FundHistoryService.NEXT)).contains("08:00", "Выбери период");
        assertThat(handle(3, FundHistoryService.TODAY)).contains("После восьми").doesNotContain("До восьми");
        assertThat(currentIds()).containsExactly(2L);
        assertThat(ledger.historyView(123, 123).from()).isEqualTo(Instant.parse("2026-10-28T05:00:00Z"));
    }
}
