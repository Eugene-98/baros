package com.baros.fund;

import com.baros.telegram.TelegramUpdateResponse.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FundCommandHandlerTests {
    @TempDir Path temp;
    private FundLedger ledger;
    private FundSyncService sync;
    private FundCommandHandler commands;

    @BeforeEach void setup() throws Exception {
        ledger = new FundLedger(temp.resolve("fund.sqlite").toString());
        sync = mock(FundSyncService.class);
        when(sync.currentBusinessDate()).thenReturn(LocalDate.of(2026, 10, 7));
        commands = new FundCommandHandler(ledger, sync, "123", "-1001", "Europe/Minsk");
    }

    private Update update(long id, long userId, long chatId, String type, String text) {
        return new Update(id, new Message(new Chat(chatId, type), text, new User(userId, "Евгений", "Жук", "eugene", false)));
    }
    private String handle(long id, String text) { return commands.handle(update(id, 123, 123, "private", text)); }

    @Test void userCanInitializeSpendAndRetryWithoutDuplication() {
        assertThat(handle(1, "/opening 1000 07.10.2026")).contains("сохранён");
        assertThat(handle(2, "/spend 25,50 Лимоны")).contains("974,50 BYN");
        assertThat(handle(2, "/spend 25,50 Лимоны")).contains("974,50 BYN");
        assertThat(handle(3, "/history month")).contains("Евгений Жук @eugene", "Лимоны");
        assertThat(handle(4, "/opening 0 07.10.2026")).contains("уже установлен");
    }

    @Test void authorizedGroupAllowsStaffButUnrelatedChatsCannotReadOrMutate() {
        assertThat(commands.handle(update(1, 456, -1001, "supergroup", "/opening 1000 07.10.2026"))).contains("сохранён");
        assertThat(commands.handle(update(2, 789, -1001, "supergroup", "+ 50 Возврат"))).contains("1050,00 BYN");
        assertThat(commands.handle(update(3, 789, 789, "private", "/balance"))).contains("Доступ");
        assertThat(commands.handle(update(4, 123, -1002, "supergroup", "/balance"))).contains("Доступ");
        assertThat(commands.handle(update(5, 789, 789, "private", "/id"))).contains("789");
        assertThat(ledger.snapshot().balance()).isEqualTo(105_000);
    }

    @Test void anonymousActorsAndInvalidAmountsOrDatesCannotWrite() {
        assertThat(commands.handle(new Update(1L, new Message(new Chat(-1001L, "supergroup"), "/opening 1000 07.10.2026", null)))).contains("личного");
        assertThat(handle(2, "/opening 1000 31.02.2026")).contains("Не понял дату");
        assertThat(handle(3, "/opening 1000 08.10.2026")).contains("позже");
        handle(4, "/opening 1000 07.10.2026");
        assertThat(handle(5, "/spend -50 Лимоны")).contains("Укажи сумму");
        assertThat(handle(6, "/spend 50")).contains("пояснение");
        assertThat(ledger.snapshot().balance()).isEqualTo(100_000);
    }

    @Test void apiOutageIsReportedWithoutBlockingSubsequentCommands() {
        handle(1, "/opening 1000 07.10.2026");
        when(sync.sync(any())).thenThrow(new IllegalStateException("API down"));
        assertThat(handle(2, "/sync")).contains("не завершена");
        assertThat(handle(3, "/add 10 Возврат")).contains("1010,00 BYN");
    }

    @Test void syncFailureReplyIncludesSafeCauseAndCheckContext() {
        handle(1, "/opening 1000 07.10.2026");
        when(sync.sync(any())).thenThrow(new com.baros.esupl.EsuplSyncException("ESUPL_DATE_RANGE",
                "Чек ID 42 находится вне запрошенного периода."));
        assertThat(handle(2, "/sync")).contains("не завершена", "ESUPL_DATE_RANGE", "Чек ID 42");
        doThrow(new IllegalStateException("Bearer secret-token")).when(sync).sync(any());
        assertThat(handle(3, "/sync")).contains("SYNC_INTERNAL").doesNotContain("secret-token");
        assertThat(ledger.snapshot().balance()).isEqualTo(100_000);
    }

    @ParameterizedTest
    @ValueSource(strings = {"20", "20.00", "20,00", "20.0", "20,0"})
    void allThreeExpensePathsAcceptTheSameMoneyFormats(String amount) {
        handle(1, "/opening 1000 07.10.2026");
        assertThat(handle(2, "-" + amount + " Лимоны")).contains("Расход записан: 20,00 BYN", "980,00 BYN");
        handle(3, "➖ Расход");
        assertThat(handle(4, amount + " Своя закупка")).contains("Своя закупка", "960,00 BYN");
        handle(5, "➖ Расход");
        assertThat(handle(6, "Рынок")).contains("только сумму");
        assertThat(handle(7, amount)).contains("Рынок", "940,00 BYN");
        assertThat(ledger.draft(123, 123)).isNull();
        assertThat(ledger.history()).hasSize(4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Хозяюшка", "Рынок", "Бар Закупка"})
    void templatesSurviveRestartAndSaveAuthor(String template) throws Exception {
        handle(1, "/opening 1000 07.10.2026");
        handle(2, "➖ Расход");
        var keyboard = commands.keyboardFor(update(2, 123, 123, "private", "➖ Расход"));
        assertThat(keyboard.keyboard().stream().flatMap(java.util.List::stream).map(b -> b.text()))
                .contains("Хозяюшка", "Рынок", "Бар Закупка", "❌ Отмена");
        handle(3, template);
        ledger = new FundLedger(temp.resolve("fund.sqlite").toString());
        commands = new FundCommandHandler(ledger, sync, "123", "-1001", "Europe/Minsk");
        assertThat(handle(4, "20,50")).contains(template, "979,50 BYN");
        var entry = ledger.history().getFirst();
        assertThat(entry.actor().id()).isEqualTo(123);
        assertThat(entry.note()).isEqualTo(template);
        assertThat(entry.cents()).isEqualTo(-2050);
        assertThat(commands.keyboardFor(update(4, 123, 123, "private", "20,50")))
                .isEqualTo(FundCommandHandler.keyboard());
    }

    @Test void invalidInputKeepsDraftAndNeverRoundsOrWrites() {
        handle(1, "/opening 1000 07.10.2026");
        handle(2, "➖ Расход");
        assertThat(handle(3, "20")).contains("пояснение");
        handle(4, "Хозяюшка");
        for (String invalid : new String[]{"20.001", "20,001", "20,00.00", "1e3", "NaN", "20руб", "-20"}) {
            handle(5, invalid);
            assertThat(ledger.snapshot().balance()).isEqualTo(100_000);
            assertThat(ledger.draft(123, 123).note()).isEqualTo("Хозяюшка");
        }
        assertThat(handle(6, "0")).contains("больше нуля");
        assertThat(handle(7, "20 Другое")).contains("только сумму");
        assertThat(handle(8, "20,5")).contains("979,50 BYN");
    }

    @Test void cancellationCustomInputAndMenuNavigationDoNotWrite() {
        handle(1, "/opening 1000 07.10.2026");
        handle(2, "➖ Расход");
        handle(3, "Рынок");
        handle(4, "✍️ Своё назначение");
        assertThat(ledger.draft(123, 123).note()).isNull();
        assertThat(handle(5, "20 Перчатки")).contains("Перчатки", "980,00 BYN");
        handle(6, "➖ Расход");
        handle(7, "❌ Отмена");
        handle(8, "20 Рынок");
        handle(9, "➖ Расход");
        handle(10, "/cancel");
        handle(11, "➖ Расход");
        handle(12, "/balance");
        handle(13, "20 Рынок");
        assertThat(ledger.draft(123, 123)).isNull();
        assertThat(ledger.history()).hasSize(2);
    }

    @Test void groupMembersAndChatsHaveIndependentDrafts() {
        handle(1, "/opening 1000 07.10.2026");
        commands.handle(update(2, 123, -1001, "supergroup", "➖ Расход"));
        commands.handle(update(3, 123, -1001, "supergroup", "Хозяюшка"));
        commands.handle(update(4, 456, -1001, "supergroup", "➖ Расход"));
        commands.handle(update(5, 456, -1001, "supergroup", "Рынок"));
        handle(6, "➕ Поступление");
        commands.handle(update(7, 123, -1002, "supergroup", "-100 Посторонний чат"));
        commands.handle(update(8, 456, -1001, "supergroup", "20"));
        commands.handle(update(9, 123, -1001, "supergroup", "30"));
        handle(10, "10 Возврат");
        assertThat(ledger.snapshot().balance()).isEqualTo(96_000);
        assertThat(ledger.history()).anySatisfy(e -> {
            assertThat(e.note()).isEqualTo("Рынок");
            assertThat(e.actor().id()).isEqualTo(456);
        });
        assertThat(ledger.history()).anySatisfy(e -> {
            assertThat(e.note()).isEqualTo("Хозяюшка");
            assertThat(e.actor().id()).isEqualTo(123);
        });
    }

    @Test void shortCommandsAcceptSpacesUnicodeWhitespaceAndIncome() {
        handle(1, "/opening 1000 07.10.2026");
        assertThat(handle(2, "- 20,50 Лимоны")).contains("979,50 BYN");
        assertThat(handle(3, "−20.50\u00a0Перчатки")).contains("959,00 BYN");
        assertThat(handle(4, "+20 Возврат")).contains("979,00 BYN");
        assertThat(handle(5, "  -20\nДлинное назначение  ")).contains("959,00 BYN");
        assertThat(handle(6, "/spend@dvoyka_bot 20 Лимоны")).contains("939,00 BYN");
    }

    @Test void eightOclockOpeningAndAutoSyncReplaceTheManualButton() throws Exception {
        ledger = new FundLedger(temp.resolve("eight.sqlite").toString(), 8);
        commands = new FundCommandHandler(ledger, sync, "123", "", "Europe/Minsk",
                new FundHistoryService(ledger, "Europe/Minsk", 8));
        assertThat(handle(1, "/opening 1000 07.10.2026")).contains("08:00", "08:05").doesNotContain("03:00", "Нажми");
        assertThat(handle(2, "/balance")).contains("08:00 — 08:00", "08:05");
        assertThat(handle(3, "🔄 Синхронизировать")).contains("автоматически", "08:05");
        verify(sync, never()).sync(any());
        assertThat(FundCommandHandler.keyboard().keyboard().stream().flatMap(java.util.List::stream).map(b -> b.text()))
                .doesNotContain("🔄 Синхронизировать");
        assertThat(handle(4, "/help")).contains("08:00", "08:05").doesNotContain("/sync", "03:00");
    }
}
