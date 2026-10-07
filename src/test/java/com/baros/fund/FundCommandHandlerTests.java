package com.baros.fund;

import com.baros.telegram.TelegramUpdateResponse.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        assertThat(handle(3, "/history")).contains("Евгений Жук @eugene [123]", "Лимоны");
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
}
