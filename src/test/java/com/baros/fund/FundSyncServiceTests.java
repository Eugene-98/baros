package com.baros.fund;

import com.baros.esupl.EsuplClient;
import com.baros.esupl.EsuplSalesResponse;
import com.baros.esupl.EsuplSyncException;
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
        when(api.getSalesForRangeStrict(any(), any(), any())).thenReturn(
                List.of(sale(10, START, true, false, "30"), sale(11, START, false, false, "10"), sale(12, START, true, true, "20")),
                List.of(sale(13, START.plusDays(1), true, false, "40")));
        assertThat(sync.sync(AUTHOR).balance()).isEqualTo(107_000);
        verify(api).getSalesForRangeStrict(START.atTime(3, 0), START.plusDays(1).atTime(2, 59, 59), ZoneId.of("Europe/Minsk"));
        verify(api).getSalesForRangeStrict(START.plusDays(1).atTime(3, 0), START.plusDays(2).atTime(2, 59, 59), ZoneId.of("Europe/Minsk"));
        verifyNoMoreInteractions(api);
        assertThat(ledger.snapshot().throughDate()).isEqualTo("2026-10-06");
    }

    @Test void apiFailureOnLaterDayLeavesEntireLedgerUntouched() {
        when(api.getSalesForRangeStrict(any(), any(), any())).thenReturn(List.of(sale(10, START, true, false, "30")))
                .thenThrow(new IllegalStateException("API unavailable"));
        assertThatThrownBy(() -> sync.sync(AUTHOR)).isInstanceOf(IllegalStateException.class);
        assertThat(ledger.snapshot().balance()).isEqualTo(100_000);
        assertThat(ledger.snapshot().lastSync()).isNull();
        assertThat(ledger.history()).hasSize(1);
    }

    @Test void repeatedAndDeletedChecksAreReconciled() {
        when(api.getSalesForRangeStrict(any(), any(), any())).thenReturn(
                List.of(sale(10, START, true, false, "30")), List.of(),
                List.of(sale(10, START, true, false, "30")), List.of(),
                List.of(sale(10, START, true, true, "30")), List.of());
        assertThat(sync.sync(AUTHOR).delta()).isEqualTo(3000);
        assertThat(sync.sync(AUTHOR).delta()).isZero();
        assertThat(sync.sync(AUTHOR).balance()).isEqualTo(100_000);
    }

    @Test void duplicateOrOutOfRangeChecksAbortWithoutChanges() {
        when(api.getSalesForRangeStrict(any(), any(), any())).thenReturn(
                List.of(sale(10, START, true, false, "30"), sale(10, START, true, false, "30")));
        assertThatThrownBy(() -> sync.sync(AUTHOR)).isInstanceOf(IllegalStateException.class);
        assertThat(ledger.snapshot().balance()).isEqualTo(100_000);
        when(api.getSalesForRangeStrict(any(), any(), any())).thenReturn(List.of(sale(11, START.minusDays(1), true, false, "30")));
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

    @Test void outOfRangeFailureShowsExactEventAndQueryWithoutWriting() {
        var check = sale(42, START, true, false, "30");
        check = new EsuplSalesResponse.Sale(check.id(), check.orderNumber(), check.type(), check.status(), false, true,
                check.totalSum(), check.totalDiscount(), OffsetDateTime.parse("2026-10-06T00:30:00Z"),
                null, null, check.payments(), check.items(), check.totalDiscounts());
        when(api.getSalesForRangeStrict(any(), any(), any())).thenReturn(List.of(check));
        assertThatThrownBy(() -> sync.sync(AUTHOR)).isInstanceOfSatisfying(EsuplSyncException.class, e ->
                assertThat(e.diagnostic()).contains("ESUPL_DATE_RANGE", "ID 42", "2026-10-06T00:30Z",
                        "Europe/Minsk: 2026-10-06T03:30", "2026-10-05T03:00", "2026-10-06T02:59:59"));
        assertThat(ledger.snapshot().balance()).isEqualTo(100_000);
        assertThat(ledger.snapshot().lastSync()).isNull();
    }

    @Test void missingDiscountsReportCheckAndPreservePreviousImport() {
        var check = sale(42, START, true, false, "30");
        when(api.getSalesForRangeStrict(any(), any(), any())).thenReturn(List.of(check), List.of());
        sync.sync(AUTHOR);
        var before = ledger.snapshot();
        var incomplete = new EsuplSalesResponse.Sale(check.id(), check.orderNumber(), check.type(), check.status(), false, true,
                check.totalSum(), check.totalDiscount(), check.eventDate(), null, null, check.payments(), check.items(), null);
        when(api.getSalesForRangeStrict(any(), any(), any())).thenReturn(List.of(incomplete));
        assertThatThrownBy(() -> sync.sync(AUTHOR)).isInstanceOfSatisfying(EsuplSyncException.class, e ->
                assertThat(e.diagnostic()).contains("ESUPL_DISCOUNTS_MISSING", "ID 42", "2026-10-05"));
        assertThat(ledger.snapshot()).isEqualTo(before);
        assertThat(ledger.history()).hasSize(2);
    }

    @Test void fractionalKopecksReportAmountErrorWithoutRoundingOrWriting() {
        when(api.getSalesForRangeStrict(any(), any(), any())).thenReturn(List.of(sale(42, START, true, false, "30.001")));
        assertThatThrownBy(() -> sync.sync(AUTHOR)).isInstanceOfSatisfying(EsuplSyncException.class, e ->
                assertThat(e.diagnostic()).contains("ESUPL_DISCOUNT_AMOUNT", "ID 42"));
        assertThat(ledger.snapshot().balance()).isEqualTo(100_000);
    }

    @Test void wrappedTimeoutAndUnknownErrorsNeverExposeRawMessages() {
        var timeout = new IllegalStateException("Bearer secret-token https://example.test/private",
                new java.util.concurrent.TimeoutException("customer-data"));
        assertThat(FundSyncService.failureDescription(timeout)).contains("ESUPL_TIMEOUT")
                .doesNotContain("secret-token", "customer-data", "https://");
        assertThat(FundSyncService.failureDescription(new IllegalStateException("Bearer secret-token")))
                .contains("SYNC_INTERNAL", "IllegalStateException").doesNotContain("secret-token");
        assertThat(FundSyncService.failureDescription(new RuntimeException("secret-token", new java.sql.SQLException("customer-data"))))
                .contains("FUND_DATABASE").doesNotContain("secret-token", "customer-data");
    }

    @Test void barDateRollsAtEightMinskTime() {
        var before = new FundSyncService(ledger, api,
                Clock.fixed(Instant.parse("2026-10-07T04:59:59Z"), ZoneId.of("Europe/Minsk")), 8, "Порча");
        var after = new FundSyncService(ledger, api,
                Clock.fixed(Instant.parse("2026-10-07T05:00:00Z"), ZoneId.of("Europe/Minsk")), 8, "Порча");
        assertThat(before.currentBusinessDate()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(after.currentBusinessDate()).isEqualTo(LocalDate.of(2026, 10, 7));
    }

    @Test void startupAndDailySyncUseSystemAuthorAndAreIdempotent() {
        when(api.getSalesForRangeStrict(any(), any(), any())).thenReturn(
                List.of(sale(10, START, true, false, "30")), List.of());
        sync.catchUpOnStartup();
        assertThat(ledger.snapshot().balance()).isEqualTo(103_000);
        assertThat(ledger.history().getFirst().actor()).isEqualTo(FundLedger.Actor.system());
        when(api.getSalesForRangeStrict(any(), any(), any())).thenReturn(
                List.of(sale(10, START, true, false, "30")), List.of());
        sync.scheduledSync();
        assertThat(ledger.snapshot().balance()).isEqualTo(103_000);
        assertThat(ledger.history()).hasSize(2);
    }

    @Test void disablingAutoSyncAlsoDisablesStartupCatchUp() {
        var disabled = new FundSyncService(ledger, api,
                Clock.fixed(Instant.parse("2026-10-07T05:05:00Z"), ZoneId.of("Europe/Minsk")), 8, "Порча", false);
        disabled.catchUpOnStartup();
        verifyNoInteractions(api);
        assertThat(ledger.snapshot().balance()).isEqualTo(100_000);
    }
}
