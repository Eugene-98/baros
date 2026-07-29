package com.baros.sales;

import com.baros.esupl.EsuplClient;
import com.baros.esupl.EsuplSalesResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.time.LocalDateTime;

@Service
public class SalesAnalyticsService {

    private final EsuplClient esuplClient;
    private final ZoneId zoneId;
    private final int dayStartHour;
    private final String currency;
    private final String doubleLabel;
    private final String otherDiscountsLabel;
    private final Set<String> doubleDiscountNames;

    public SalesAnalyticsService(
            EsuplClient esuplClient,
            @Value("${bar.time-zone}") String timeZone,
            @Value("${bar.day-start-hour}") int dayStartHour,
            @Value("${bar.currency}") String currency,
            @Value("${bar.double.label}") String doubleLabel,
            @Value("${bar.double.discount-names}") String doubleDiscountNames,
            @Value("${bar.other-discounts-label}") String otherDiscountsLabel
    ) {
        this.esuplClient = esuplClient;
        this.zoneId = ZoneId.of(timeZone);
        this.dayStartHour = dayStartHour;
        this.currency = currency;
        this.doubleLabel = doubleLabel;
        this.otherDiscountsLabel = otherDiscountsLabel;

        this.doubleDiscountNames = List.of(doubleDiscountNames.split(","))
                .stream()
                .map(this::normalize)
                .collect(Collectors.toSet());
    }

    public String formatTodaySummary() {
        LocalDate businessDate = getCurrentBusinessDate();
        return formatDaySummary(businessDate);
    }

    public String formatYesterdaySummary() {
        LocalDate businessDate = getCurrentBusinessDate().minusDays(1);
        return formatDaySummary(businessDate);
    }

    public String formatDaySummary(LocalDate businessDate) {
        SalesSummary summary = getSummaryForBusinessDate(businessDate);
        return formatSummary("Отчет за барный день: " + summary.businessDate(), summary);
    }

    public String formatMonthSummary(YearMonth month) {
        LocalDateTime start = month.atDay(1).atStartOfDay();
        LocalDateTime end = month.atEndOfMonth().atTime(23, 59, 59);

        List<EsuplSalesResponse.Sale> sales = getCleanSalesForRange(start, end);

        SalesSummary monthSummary = buildSummary(month.atDay(1), sales);

        return formatSummary("Отчет за месяц: " + month, monthSummary);
    }

    public SalesSummary getSummaryForBusinessDate(LocalDate businessDate) {
        List<EsuplSalesResponse.Sale> sales = getCleanSalesForBusinessDate(businessDate);
        return buildSummary(businessDate, sales);
    }

    private List<EsuplSalesResponse.Sale> getCleanSalesForBusinessDate(LocalDate businessDate) {
        return esuplClient.getSalesForBusinessDate(businessDate)
                .stream()
                .filter(this::isReportableSale)
                .toList();
    }

    private BigDecimal getDoubleAmount(EsuplSalesResponse.Sale sale) {
        if (sale.totalDiscounts() == null) {
            return BigDecimal.ZERO;
        }

        return sale.totalDiscounts()
                .stream()
                .filter(discount -> isDoubleDiscount(discount.name()))
                .map(EsuplSalesResponse.TotalDiscount::amount)
                .filter(amount -> amount != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal getOtherDiscountsAmount(EsuplSalesResponse.Sale sale) {
        if (sale.totalDiscounts() == null) {
            return BigDecimal.ZERO;
        }

        return sale.totalDiscounts()
                .stream()
                .filter(discount -> !isDoubleDiscount(discount.name()))
                .map(EsuplSalesResponse.TotalDiscount::amount)
                .filter(amount -> amount != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private boolean isDoubleDiscount(String discountName) {
        if (discountName == null) {
            return false;
        }

        return doubleDiscountNames.contains(normalize(discountName));
    }

    private String formatSummary(String title, SalesSummary summary) {
        return title + "\n\n"
                + "Продажи: " + money(summary.revenue()) + "\n"
                + doubleLabel + ": " + money(summary.doubleAmount()) + "\n"
                + otherDiscountsLabel + ": " + money(summary.otherDiscountsAmount()) + "\n\n"
                + "Чеков всего: " + summary.totalChecks() + "\n"
                + "Оплаченных чеков: " + summary.paidChecks() + "\n"
                + "Средний чек: " + money(summary.averageCheck());
    }

    private LocalDate getCurrentBusinessDate() {
        ZonedDateTime now = ZonedDateTime.now(zoneId);

        if (now.getHour() < dayStartHour) {
            return now.toLocalDate().minusDays(1);
        }

        return now.toLocalDate();
    }

    private String money(BigDecimal value) {
        if (value == null) {
            value = BigDecimal.ZERO;
        }

        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.forLanguageTag("ru-RU"));
        DecimalFormat formatter = new DecimalFormat("#,##0.00", symbols);

        return formatter.format(value) + " " + currency;
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.trim().toLowerCase(Locale.ROOT);
    }

    private List<EsuplSalesResponse.Sale> getCleanSalesForRange(LocalDateTime start, LocalDateTime end) {
        return esuplClient.getSalesForRange(start, end)
                .stream()
                .filter(this::isReportableSale)
                .toList();
    }

    private SalesSummary buildSummary(LocalDate businessDate, List<EsuplSalesResponse.Sale> sales) {
        BigDecimal revenue = sales.stream()
                .map(this::getNetSalesAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal doubleAmount = sales.stream()
                .map(this::getDoubleAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal otherDiscountsAmount = sales.stream()
                .map(this::getOtherDiscountsAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long paidChecks = sales.stream()
                .filter(sale -> sale.paidAmount().compareTo(BigDecimal.ZERO) > 0)
                .count();

        BigDecimal averageCheck = sales.isEmpty()
                ? BigDecimal.ZERO
                : revenue.divide(BigDecimal.valueOf(sales.size()), 2, RoundingMode.HALF_UP);

        return new SalesSummary(
                businessDate,
                sales.size(),
                paidChecks,
                revenue,
                doubleAmount,
                otherDiscountsAmount,
                averageCheck
        );
    }

    private BigDecimal getNetSalesAmount(EsuplSalesResponse.Sale sale) {
        BigDecimal totalSum = safe(sale.totalSum());

        BigDecimal totalDiscount = sale.totalDiscount() != null
                ? sale.totalDiscount()
                : getDoubleAmount(sale).add(getOtherDiscountsAmount(sale));

        return totalSum.subtract(totalDiscount);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private boolean isReportableSale(EsuplSalesResponse.Sale sale) {
        if (sale == null) {
            return false;
        }

        return !sale.deleted();
    }
}