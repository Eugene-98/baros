package com.baros.sales;

import com.baros.esupl.EsuplSalesResponse;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SalesSummary(
        LocalDate businessDate,
        int totalChecks,
        long paidChecks,
        BigDecimal revenue,
        BigDecimal gross,
        BigDecimal discounts,
        BigDecimal averageCheck,
        EsuplSalesResponse.Sale lastSale
) {
}