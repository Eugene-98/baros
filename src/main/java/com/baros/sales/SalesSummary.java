package com.baros.sales;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SalesSummary(
        LocalDate businessDate,
        int totalChecks,
        long paidChecks,
        BigDecimal revenue,
        BigDecimal doubleAmount,
        BigDecimal otherDiscountsAmount,
        BigDecimal averageCheck
) {
}