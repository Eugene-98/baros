package com.baros.fund;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;

class FundLedgerTests {
    @TempDir Path temp;
    private static final LocalDate START = LocalDate.of(2026, 10, 1);
    private static final FundLedger.Actor AUTHOR = new FundLedger.Actor(123, "Евгений @eugene");
    private FundLedger ledger() throws Exception { return new FundLedger(temp.resolve("fund.sqlite").toString()); }
    private Map<Long, FundLedger.SaleAmount> sale(long cents) {
        return Map.of(10L, new FundLedger.SaleAmount(cents, START));
    }

    @Test void repeatedSyncPreservesOpeningAndManualEntries() throws Exception {
        var ledger = ledger();
        ledger.opening(1, 100_000, START, AUTHOR);
        ledger.manual(2, -2_550, "Лимоны", AUTHOR);
        ledger.manual(3, 5_000, "Возврат", AUTHOR);
        assertThat(ledger.reconcile(sale(3_000), START, START, AUTHOR).balance()).isEqualTo(105_450);
        var again = ledger.reconcile(sale(3_000), START, START, AUTHOR);
        assertThat(again.delta()).isZero();
        assertThat(again.changes()).isZero();
        assertThat(again.balance()).isEqualTo(105_450);
        assertThat(ledger.history()).hasSize(4);
    }

    @Test void editedAndDeletedChecksProduceAuditedDeltas() throws Exception {
        var ledger = ledger();
        ledger.opening(1, 100_000, START, AUTHOR);
        ledger.reconcile(sale(3_000), START, START, AUTHOR);
        assertThat(ledger.reconcile(sale(2_000), START, START, AUTHOR).delta()).isEqualTo(-1_000);
        assertThat(ledger.reconcile(Map.of(), START, START, AUTHOR).balance()).isEqualTo(100_000);
        assertThat(ledger.history()).extracting(FundLedger.Entry::cents).containsExactly(-2_000L, -1_000L, 3_000L, 100_000L);
    }

    @Test void openingCannotBeResetAndTelegramRetriesSurviveRestart() throws Exception {
        var ledger = ledger();
        ledger.opening(1, 100_000, START, AUTHOR);
        ledger.manual(2, -5_000, "Такси", AUTHOR);
        ledger.acknowledge(2);
        var restarted = ledger();
        restarted.manual(2, -5_000, "Такси", AUTHOR);
        restarted.opening(1, 100_000, START, AUTHOR);
        assertThat(restarted.snapshot().balance()).isEqualTo(95_000);
        assertThat(restarted.offset()).isEqualTo(3);
        assertThat(restarted.history().getFirst().actor()).isEqualTo(AUTHOR);
        assertThatThrownBy(() -> restarted.opening(4, 0, START, AUTHOR)).isInstanceOf(FundLedger.InputException.class);
        assertThat(restarted.snapshot().balance()).isEqualTo(95_000);
    }

    @Test void invalidSnapshotRollsBackAllEntriesAndSyncTimestamp() throws Exception {
        var ledger = ledger();
        ledger.opening(1, 100_000, START, AUTHOR);
        var invalid = Map.of(10L, new FundLedger.SaleAmount(1000, START),
                11L, new FundLedger.SaleAmount(500, START.minusDays(1)));
        assertThatThrownBy(() -> ledger.reconcile(invalid, START, START, AUTHOR)).isInstanceOf(IllegalStateException.class);
        assertThat(ledger.snapshot().balance()).isEqualTo(100_000);
        assertThat(ledger.snapshot().lastSync()).isNull();
        assertThat(ledger.history()).hasSize(1);
    }

    @Test void shorterScanDoesNotReverseDaysOutsideCoverage() throws Exception {
        var ledger = ledger();
        ledger.opening(1, 0, START, AUTHOR);
        ledger.reconcile(Map.of(10L, new FundLedger.SaleAmount(1000, START.plusDays(1))), START, START.plusDays(1), AUTHOR);
        assertThat(ledger.reconcile(Map.of(), START, START, AUTHOR).balance()).isEqualTo(1000);
    }

    @Test void concurrentExpensesAndRetriesDoNotLoseOrDuplicateMoney() throws Exception {
        var ledger = ledger();
        ledger.opening(1, 100_000, START, AUTHOR);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var jobs = IntStream.range(0, 60).mapToObj(i -> pool.submit(() -> ledger.manual(10 + i % 30, -100, "Тест", AUTHOR))).toList();
            for (var job : jobs) job.get();
        }
        assertThat(ledger.snapshot().balance()).isEqualTo(97_000);
    }

    @Test void amountsAreExactAndInvalidInputIsRejected() throws Exception {
        assertThat(FundLedger.parseMoney("25,50", false)).isEqualTo(2550);
        assertThat(FundLedger.parseMoney("-1.01", true)).isEqualTo(-101);
        for (String value : new String[]{"1.005", "1e4", "NaN", "-1", "1000000000"}) {
            assertThatThrownBy(() -> FundLedger.parseMoney(value, false)).isInstanceOf(FundLedger.InputException.class);
        }
        var ledger = ledger();
        assertThatThrownBy(() -> ledger.manual(1, 100, "Тест", AUTHOR)).isInstanceOf(FundLedger.InputException.class);
        ledger.opening(1, 0, START, AUTHOR);
        assertThatThrownBy(() -> ledger.manual(2, 0, "Тест", AUTHOR)).isInstanceOf(FundLedger.InputException.class);
        assertThatThrownBy(() -> ledger.manual(2, 100, " ", AUTHOR)).isInstanceOf(FundLedger.InputException.class);
    }
}
