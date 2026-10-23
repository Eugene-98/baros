package com.baros.fund;

import com.baros.esupl.EsuplClient;
import com.baros.esupl.EsuplSalesResponse;
import com.baros.esupl.EsuplSyncException;
import org.springframework.core.codec.DecodingException;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.TimeoutException;
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
                    throw new EsuplSyncException("ESUPL_CHECK_FIELDS", "У чека отсутствует корректный ID или event_date. Барный день: "
                            + day + "; ID: " + (sale == null ? "не указан" : sale.id()) + ".");
                }
                LocalDateTime event = sale.eventDate().atZoneSameInstant(clock.getZone()).toLocalDateTime();
                if (event.isBefore(start) || !event.isBefore(start.plusDays(1))) {
                    throw new EsuplSyncException("ESUPL_DATE_RANGE", "Чек ID " + sale.id()
                            + " находится вне запрошенного периода.\nВремя event_date: " + sale.eventDate()
                            + "; в " + clock.getZone() + ": " + event
                            + ".\nЗапрошено: " + start + " — " + end + " (" + clock.getZone() + ").");
                }
                if (!seen.add(sale.id())) throw new EsuplSyncException("ESUPL_DUPLICATE",
                        "Чек ID " + sale.id() + " повторился при сверке барного дня " + day + ".");
                if (sale.deleted() || !sale.paid()) continue;
                // The include is mandatory: absent data must not erase previous imports.
                if (sale.totalDiscounts() == null) {
                    if (sale.totalDiscount() != null && sale.totalDiscount().signum() == 0) continue;
                    throw new EsuplSyncException("ESUPL_DISCOUNTS_MISSING", "У чека ID " + sale.id()
                            + " отсутствует список total_discounts. Барный день: " + day + ".");
                }
                BigDecimal total = BigDecimal.ZERO;
                for (EsuplSalesResponse.TotalDiscount discount : sale.totalDiscounts()) {
                    if (discount == null || discount.name() == null || discount.amount() == null) {
                        throw new EsuplSyncException("ESUPL_DISCOUNT_FIELDS", "У скидки в чеке ID " + sale.id()
                                + " отсутствует название или сумма. Барный день: " + day + ".");
                    }
                    if (discountNames.contains(normalize(discount.name()))) total = total.add(discount.amount());
                }
                long cents;
                try {
                    cents = total.movePointRight(2).longValueExact();
                } catch (ArithmeticException e) {
                    throw new EsuplSyncException("ESUPL_DISCOUNT_AMOUNT", "Сумму скидки в чеке ID " + sale.id()
                            + " нельзя точно сохранить в копейках. Барный день: " + day + ".");
                }
                if (cents < 0) throw new EsuplSyncException("ESUPL_DISCOUNT_AMOUNT", "Отрицательная скидка в чеке ID "
                        + sale.id() + ". Барный день: " + day + ".");
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
            log.error("Fund sync failed: {}", failureDescription(e));
        }
    }

    /** Only our explicit diagnostics are safe to expose; arbitrary exception messages may contain secrets. */
    static String failureDescription(Throwable error) {
        List<Throwable> causes = new ArrayList<>();
        for (Throwable cause = error; cause != null && causes.size() < 20 && !causes.contains(cause); cause = cause.getCause()) {
            causes.add(cause);
            if (cause instanceof EsuplSyncException known) return known.diagnostic();
        }
        if (causes.stream().anyMatch(e -> e instanceof TimeoutException || e instanceof java.net.SocketTimeoutException)) {
            return "Код: ESUPL_TIMEOUT\nESUPL не ответил за отведённое время. Попробуй синхронизацию позже.";
        }
        for (Throwable cause : causes) {
            if (cause instanceof WebClientResponseException http) {
                return "Код: ESUPL_HTTP_" + http.getStatusCode().value() + "\nESUPL ответил HTTP " + http.getStatusCode().value() + ".";
            }
            if (cause instanceof DataBufferLimitException) {
                return "Код: ESUPL_RESPONSE_SIZE\nОтвет ESUPL превысил допустимый размер.";
            }
            if (cause instanceof DecodingException) {
                return "Код: ESUPL_RESPONSE_FORMAT\nНе удалось прочитать формат данных ESUPL.";
            }
            if (cause instanceof WebClientRequestException) {
                return "Код: ESUPL_CONNECTION\nНе удалось подключиться к ESUPL. Попробуй синхронизацию позже.";
            }
            if (cause instanceof SQLException) {
                return "Код: FUND_DATABASE\nНе удалось прочитать или сохранить журнал фонда.";
            }
        }
        String location = Arrays.stream(error.getStackTrace()).filter(frame -> frame.getClassName().startsWith("com.baros."))
                .findFirst().map(frame -> " · " + frame.getClassName() + ":" + frame.getLineNumber()).orElse("");
        return "Код: SYNC_INTERNAL\nВнутренняя ошибка: " + error.getClass().getSimpleName() + location + ".";
    }

    private static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
