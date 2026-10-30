package com.baros.fund;

import com.baros.telegram.TelegramClient;
import com.baros.telegram.TelegramUpdateResponse.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FundPollingServiceTests {
    @TempDir Path temp;

    @ParameterizedTest
    @ValueSource(strings = {"/spend 25,50 Лимоны", "-25,50 Лимоны", "25,50 Лимоны", "25,50"})
    void failedReplyAndRestartReplayExpenseWithoutChargingTwice(String input) throws Exception {
        String path = temp.resolve("fund.sqlite").toString();
        var ledger = new FundLedger(path);
        ledger.opening(1, 100_000, LocalDate.of(2026, 10, 7), new FundLedger.Actor(123, "Автор"));
        var telegram = mock(TelegramClient.class);
        var sync = mock(FundSyncService.class);
        var commands = new FundCommandHandler(ledger, sync, "123", "", "Europe/Minsk");
        if (!input.startsWith("/") && !input.startsWith("-")) {
            ledger.saveDraft(123, 123, new FundLedger.Draft(true, input.equals("25,50") ? "Лимоны" : null));
        }
        var update = new Update(2L, new Message(new Chat(123L, "private"), input, new User(123L, "Евгений", null, null, false)));
        when(telegram.getUpdates(0)).thenReturn(List.of(update));
        doThrow(new IllegalStateException("Telegram unavailable")).doNothing().when(telegram).sendMessage(anyLong(), anyString(), any());
        var poller = new FundPollingService(telegram, commands, ledger);
        assertThatThrownBy(poller::pollOnce).isInstanceOf(IllegalStateException.class);
        assertThat(ledger.snapshot().balance()).isEqualTo(97_450);
        assertThat(ledger.offset()).isZero();
        var restarted = new FundLedger(path);
        var restartedCommands = new FundCommandHandler(restarted, sync, "123", "", "Europe/Minsk");
        new FundPollingService(telegram, restartedCommands, restarted).pollOnce();
        assertThat(restarted.snapshot().balance()).isEqualTo(97_450);
        assertThat(restarted.offset()).isEqualTo(3);
        assertThat(restarted.history()).hasSize(2);
        assertThat(restarted.draft(123, 123)).isNull();
        verify(telegram, times(2)).sendMessage(eq(123L), contains("Расход записан: 25,50 BYN"), eq(FundCommandHandler.keyboard()));
    }

    @Test void nonMessageUpdatesAreAcknowledgedWithoutFinancialWrites() throws Exception {
        var ledger = new FundLedger(temp.resolve("fund.sqlite").toString());
        var telegram = mock(TelegramClient.class);
        var commands = mock(FundCommandHandler.class);
        when(telegram.getUpdates(0)).thenReturn(List.of(new Update(20L, null)));
        new FundPollingService(telegram, commands, ledger).pollOnce();
        assertThat(ledger.offset()).isEqualTo(21);
        assertThat(ledger.history()).isEmpty();
        verifyNoInteractions(commands);
    }
}
