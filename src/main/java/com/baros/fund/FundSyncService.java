package com.baros.fund;

import com.baros.esupl.EsuplClient;
import com.baros.esupl.EsuplSalesResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Profile("dvoyka")
public class FundSyncService {
    private static final Logger log = LoggerFactory.getLogger(FundSyncService.class);
    private final FundLedger ledger;
    private final EsuplClient esupl;
    private final Clock clock;
    private final int dayStartHour;
    private final Set<String> discountNames;

    @Autowired
    public FundSyncService(FundLedger ledger, EsuplClient esupl,
                           @Value("${bar.time-zone}") String zone,
                           @Value("${bar.day-start-hour}") int dayStartHour,
                           @Value("${bar.double.discount-names}") String names) {
        this(ledger, esupl, Clock.system(ZoneId.of(zone)), dayStartHour, names);
    }

    // A fixed clock makes the bar-day boundary testable without contacting ESUPL.
    FundSyncService(FundLedger ledger, EsuplClient esupl, Clock clock, int dayStartHour, String names) {
        this.ledger = ledger;
        this.esupl = esupl;
        this.clock = clock;
        this.dayStartHour = dayStartHour;
        discountNames = Arrays.stream(names.split(",")).map(FundSyncService::normalize).collect(Collectors.toSet());
    }

    public LocalDate currentBusinessDate() {
        return ZonedDateTime.now(clock).minusHours(dayStartHour).toLocalDate();
    }

    public synchronized FundLedger.SyncResult sync(FundLedger.Actor actor) {
        FundLedger.Snapshot state = ledger.snapshot();
        if (state.startDate() == null) throw new FundLedger.InputException("Сначала установи начальный остаток: /opening СУММА ДД.ММ.ГГГГ");
        LocalDate through = currentBusinessDate().minusDays(1);
        if (through.isBefore(state.startDate())) {
            throw new FundLedger.InputException("Пока нет завершённых барных дней после даты отсчёта. Синхронизация станет доступна после 03:00.");
        }
        Map<Long, FundLedger.SaleAmount> amounts = new HashMap<>();
        Set<Long> seen = new HashSet<>();
        // Re-read completed days so edited/deleted checks are reconciled too.
        // No writes happen until every page of every day has been fetched successfully.
        for (LocalDate day = state.startDate(); !day.isAfter(through); day = day.plusDays(1)) {
            LocalDateTime start = day.atTime(dayStartHour, 0);
            LocalDateTime end = day.plusDays(1).atTime(dayStartHour, 0).minusSeconds(1);
            for (EsuplSalesResponse.Sale sale : esupl.getSalesForRangeStrict(start, end)) {
                if (sale == null || sale.id() == null || sale.id() <= 0 || sale.eventDate() == null) {
                    throw new IllegalStateException("ESUPL check has no identity/date; sync aborted");
                }
                LocalDateTime event = sale.eventDate().atZoneSameInstant(clock.getZone()).toLocalDateTime();
                if (event.isBefore(start) || !event.isBefore(start.plusDays(1))) {
                    throw new IllegalStateException("ESUPL returned a check outside the requested bar day");
                }
                if (!seen.add(sale.id())) throw new IllegalStateException("ESUPL returned a duplicate check; retry sync");
                if (sale.deleted() || !sale.paid()) continue;
                // The include is mandatory: absent data must not erase previous imports.
                if (sale.totalDiscounts() == null) {
                    if (sale.totalDiscount() != null && sale.totalDiscount().signum() == 0) continue;
                    throw new IllegalStateException("ESUPL omitted total_discounts");
                }
                BigDecimal total = BigDecimal.ZERO;
                for (EsuplSalesResponse.TotalDiscount discount : sale.totalDiscounts()) {
                    if (discount == null || discount.name() == null || discount.amount() == null) {
                        throw new IllegalStateException("ESUPL returned an incomplete discount");
                    }
                    if (discountNames.contains(normalize(discount.name()))) total = total.add(discount.amount());
                }
                long cents = total.movePointRight(2).longValueExact();
                if (cents < 0) throw new IllegalStateException("ESUPL returned a negative discount");
                if (cents != 0) amounts.put(sale.id(), new FundLedger.SaleAmount(cents, day));
            }
        }
        return ledger.reconcile(amounts, state.startDate(), through, actor);
    }

    @Scheduled(cron = "${fund.sync.cron}", zone = "${bar.time-zone}")
    public void scheduledSync() {
        try {
            if (ledger.snapshot().startDate() == null) return;
            FundLedger.SyncResult result = sync(FundLedger.Actor.system());
            log.info("Fund sync completed: {} changes", result.changes());
        } catch (Exception e) {
            // Do not log HTTP exception messages: they can contain authorization URLs or sales data.
            log.error("Fund sync failed ({}); ledger remains unchanged", e.getClass().getSimpleName());
        }
    }

    private static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
