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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class SalesAnalyticsService {

    private final EsuplClient esuplClient;
    private final ZoneId zoneId;
    private final int dayStartHour;
    private final String currency;

    public SalesAnalyticsService(
            EsuplClient esuplClient,
            @Value("${bar.time-zone}") String timeZone,
            @Value("${bar.day-start-hour}") int dayStartHour,
            @Value("${bar.currency}") String currency
    ) {
        this.esuplClient = esuplClient;
        this.zoneId = ZoneId.of(timeZone);
        this.dayStartHour = dayStartHour;
        this.currency = currency;
    }

    public SalesSummary getTodaySummary() {
        LocalDate businessDate = getCurrentBusinessDate();
        return getSummaryForBusinessDate(businessDate);
    }

    public SalesSummary getSummaryForBusinessDate(LocalDate businessDate) {
        List<EsuplSalesResponse.Sale> sales = esuplClient.getSalesForBusinessDate(businessDate)
                .stream()
                .filter(sale -> "sale".equals(sale.type()))
                .filter(sale -> "closed".equals(sale.status()))
                .filter(sale -> !sale.deleted())
                .toList();

        BigDecimal gross = sales.stream()
                .map(EsuplSalesResponse.Sale::totalSum)
                .filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal discounts = sales.stream()
                .map(EsuplSalesResponse.Sale::totalDiscount)
                .filter(value -> value != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal revenue = sales.stream()
                .map(EsuplSalesResponse.Sale::paidAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long paidChecks = sales.stream()
                .filter(sale -> sale.paidAmount().compareTo(BigDecimal.ZERO) > 0)
                .count();

        BigDecimal averageCheck = paidChecks == 0
                ? BigDecimal.ZERO
                : revenue.divide(BigDecimal.valueOf(paidChecks), 2, RoundingMode.HALF_UP);

        Comparator<EsuplSalesResponse.Sale> byEventDate =
                Comparator.comparing(
                        EsuplSalesResponse.Sale::eventDate,
                        Comparator.nullsFirst(Comparator.naturalOrder())
                );

        EsuplSalesResponse.Sale lastSale = sales.stream()
                .max(byEventDate)
                .orElse(null);

        return new SalesSummary(
                businessDate,
                sales.size(),
                paidChecks,
                revenue,
                gross,
                discounts,
                averageCheck,
                lastSale
        );
    }

    public String formatTodaySummary() {
        SalesSummary summary = getTodaySummary();

        StringBuilder text = new StringBuilder();

        text.append("Отчет за барный день: ")
                .append(summary.businessDate())
                .append("\n\n");

        text.append("Фактическая выручка: ")
                .append(money(summary.revenue()))
                .append("\n");

        text.append("Валовая сумма: ")
                .append(money(summary.gross()))
                .append("\n");

        text.append("Скидки/списания: ")
                .append(money(summary.discounts()))
                .append("\n\n");

        text.append("Чеков всего: ")
                .append(summary.totalChecks())
                .append("\n");

        text.append("Оплаченных чеков: ")
                .append(summary.paidChecks())
                .append("\n");

        text.append("Средний чек: ")
                .append(money(summary.averageCheck()))
                .append("\n");

        if (summary.lastSale() != null) {
            EsuplSalesResponse.Sale sale = summary.lastSale();

            String waiter = sale.user() == null ? "-" : sale.user().fullName();
            String time = sale.eventDate() == null
                    ? "-"
                    : sale.eventDate()
                    .atZoneSameInstant(zoneId)
                    .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));

            text.append("\nПоследний чек:\n");
            text.append("№ ").append(sale.orderNumber()).append("\n");
            text.append("Время: ").append(time).append("\n");
            text.append("Сумма оплат: ").append(money(sale.paidAmount())).append("\n");
            text.append("Сотрудник: ").append(waiter).append("\n");
        }

        return text.toString();
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
}