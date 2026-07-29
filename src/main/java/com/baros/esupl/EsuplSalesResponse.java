package com.baros.esupl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EsuplSalesResponse(
        List<Sale> data
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Sale(
            Long id,

            @JsonProperty("order_number")
            String orderNumber,

            String type,
            String status,

            @JsonProperty("is_deleted")
            boolean deleted,

            @JsonProperty("is_paid")
            boolean paid,

            @JsonProperty("total_sum")
            BigDecimal totalSum,

            @JsonProperty("total_discount")
            BigDecimal totalDiscount,

            @JsonProperty("event_date")
            OffsetDateTime eventDate,

            User user,
            Table table,
            List<Payment> payments,
            List<SaleItem> items,

            @JsonProperty("total_discounts")
            List<TotalDiscount> totalDiscounts
    ) {
        public BigDecimal paidAmount() {
            if (payments == null) {
                return BigDecimal.ZERO;
            }

            return payments.stream()
                    .map(Payment::amount)
                    .filter(amount -> amount != null)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record User(
            Long id,
            @JsonProperty("full_name")
            String fullName
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Payment(
            Long id,
            @JsonProperty("payment_type")
            String paymentType,
            BigDecimal amount,
            @JsonProperty("payment_method")
            PaymentMethod paymentMethod
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentMethod(
            Long id,
            String name
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SaleItem(
            Item item,
            BigDecimal quantity,
            BigDecimal cost,
            @JsonProperty("total_discount")
            BigDecimal totalDiscount
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(
            Long id,
            String name
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Table(
            String name,
            Hall hall
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Hall(
            String name
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TotalDiscount(
            Long id,
            String name,
            BigDecimal amount
    ) {
    }
}