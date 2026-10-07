package com.baros.fund;

import com.baros.esupl.EsuplClient;
import com.baros.esupl.EsuplSalesResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FundSyncServiceTests {
    @TempDir Path temp;
    private FundLedger ledger;
    private EsuplClient api;
    private FundSyncService sync;
    private static final LocalDate START = LocalDate.of(2026, 10, 5);
    private static final FundLedger.Actor AUTHOR = new FundLedger.Actor(1, "Автор");

    @BeforeEach void setup() throws Exception {
        ledger = new FundLedger(temp.resolve("fund.sqlite").toString());
        api = mock(EsuplClient.class);
        sync = new FundSyncService(ledger, api,
                Clock.fixed(Instant.parse("2026-10-07T05:05:00Z"), ZoneId.of("Europe/Minsk")), 3, "Порча");
        ledger.opening(1, 100_000, START, AUTHOR);
    }

    private EsuplSalesResponse.Sale sale(long id, LocalDate day, boolean paid, boolean deleted, String amount) {
        return new EsuplSalesResponse.Sale(id, "1", "sale", "closed", deleted, paid,
                new BigDecimal("100"), new BigDecimal(amount), day.atTime(20, 0).atOffset(ZoneOffset.ofHours(3)),
                null, null, List.of(), List.of(), List.of(
                new EsuplSalesResponse.TotalDiscount(1L, " Порча ", new BigDecimal(amount)),
                new EsuplSalesResponse.TotalDiscount(2L, "Персонал", new BigDecimal("50"))));
    }

    @Test void scansOnlyCompletedDaysAndImportsOnlyPaidNonDeletedPorcha() {
        when(api.getSalesForRangeStrict(any(), any())).thenReturn(
                List.of(sale(10, START, true, false, "30"), sale(11, START, false, false, "10"), sale(12, START, true, true, "20")),
                List.of(sale(13, START.plusDays(1), true, false, "40")));
        assertThat(sync.sync(AUTHOR).balance()).isEqualTo(107_000);
        verify(api).getSalesForRangeStrict(START.atTime(3, 0), START.plusDays(1).atTime(2, 59, 59));
        verify(api).getSalesForRangeStrict(START.plusDays(1).atTime(3, 0), START.plusDays(2).atTime(2, 59, 59));
        verifyNoMoreInteractions(api);
        assertThat(ledger.snapshot().throughDate()).isEqualTo("2026-10-06");
    }

    @Test void apiFailureOnLaterDayLeavesEntireLedgerUntouched() {
        when(api.getSalesForRangeStrict(any(), any())).thenReturn(List.of(sale(10, START, true, false, "30")))
                .thenThrow(new IllegalStateException("API unavailable"));
        assertThatThrownBy(() -> sync.sync(AUTHOR)).isInstanceOf(IllegalStateException.class);
        assertThat(ledger.snapshot().balance()).isEqualTo(100_000);
        assertThat(ledger.snapshot().lastSync()).isNull();
        assertThat(ledger.history()).hasSize(1);
    }

    @Test void repeatedAndDeletedChecksAreReconciled() {
        when(api.getSalesForRangeStrict(any(), any())).thenReturn(
                List.of(sale(10, START, true, false, "30")), List.of(),
                List.of(sale(10, START, true, false, "30")), List.of(),
                List.of(sale(10, START, true, true, "30")), List.of());
        assertThat(sync.sync(AUTHOR).delta()).isEqualTo(3000);
        assertThat(sync.sync(AUTHOR).delta()).isZero();
        assertThat(sync.sync(AUTHOR).balance()).isEqualTo(100_000);
    }

    @Test void duplicateOrOutOfRangeChecksAbortWithoutChanges() {
        when(api.getSalesForRangeStrict(any(), any())).thenReturn(
                List.of(sale(10, START, true, false, "30"), sale(10, START, true, false, "30")));
        assertThatThrownBy(() -> sync.sync(AUTHOR)).isInstanceOf(IllegalStateException.class);
        assertThat(ledger.snapshot().balance()).isEqualTo(100_000);
        when(api.getSalesForRangeStrict(any(), any())).thenReturn(List.of(sale(11, START.minusDays(1), true, false, "30")));
        assertThatThrownBy(() -> sync.sync(AUTHOR)).isInstanceOf(IllegalStateException.class);
    }

    @Test void barDateRollsAtThreeMinskTime() {
        var before = new FundSyncService(ledger, api,
                Clock.fixed(Instant.parse("2026-10-06T23:59:59Z"), ZoneId.of("Europe/Minsk")), 3, "Порча");
        var after = new FundSyncService(ledger, api,
                Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneId.of("Europe/Minsk")), 3, "Порча");
        assertThat(before.currentBusinessDate()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(after.currentBusinessDate()).isEqualTo(LocalDate.of(2026, 10, 7));
    }
}
