package com.baros.esupl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

@Service
public class EsuplClient {

    private static final DateTimeFormatter API_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String INCLUDE = String.join(",",
            "user:fields(id|full_name)",
            "items",
            "terminal:fields(id|name)",
            "outlet:fields(id|name)",
            "customer:fields(id|full_name|formatted_phone)",
            "payments:fields(id|payment_type|amount)",
            "payments.payment_method:fields(id|name)",
            "total_discounts",
            "table:fields(name)",
            "table.hall:fields(name)"
    );

    private final WebClient webClient;
    private final long teamId;

    public EsuplClient(
            @Value("${esupl.base-url}") String baseUrl,
            @Value("${esupl.token}") String token,
            @Value("${esupl.team-id}") long teamId
    ) {
        this.teamId = teamId;

        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .codecs(configurer -> configurer
                        .defaultCodecs()
                        .maxInMemorySize(10 * 1024 * 1024)
                )
                .build();
    }

    public List<EsuplSalesResponse.Sale> getSalesForRange(LocalDateTime start, LocalDateTime end) {
        return getSalesForRange(start, end, false);
    }

    // Fund reconciliation must never mistake a malformed API response for an empty day.
    public List<EsuplSalesResponse.Sale> getSalesForRangeStrict(LocalDateTime start, LocalDateTime end) {
        return getSalesForRange(start, end, true);
    }

    /**
     * ESUPL may return neighbouring checks for a time-filtered request. Fetch complete
     * calendar dates covering both UTC and bar-local representations, then filter by
     * instants after ALL pages have been read. The end argument includes its whole second.
     */
    public List<EsuplSalesResponse.Sale> getSalesForRangeStrict(LocalDateTime start, LocalDateTime end, ZoneId zone) {
        if (end.isBefore(start)) throw new IllegalArgumentException("Invalid sales range");
        Instant from = start.atZone(zone).toInstant();
        Instant until = end.plusSeconds(1).atZone(zone).toInstant();
        LocalDate utcStart = from.atOffset(ZoneOffset.UTC).toLocalDate();
        LocalDate utcEnd = end.atZone(zone).withZoneSameInstant(ZoneOffset.UTC).toLocalDate();
        LocalDateTime queryStart = (utcStart.isBefore(start.toLocalDate()) ? utcStart : start.toLocalDate()).atStartOfDay();
        LocalDateTime queryUntil = (utcEnd.isAfter(end.toLocalDate()) ? utcEnd : end.toLocalDate()).plusDays(1).atStartOfDay();

        // Reject responses that even ignore the expanded date window, rather than treating
        // an unrelated response as an empty day and reversing previously imported amounts.
        Instant earliest = queryStart.atZone(zone).toInstant().isBefore(queryStart.toInstant(ZoneOffset.UTC))
                ? queryStart.atZone(zone).toInstant() : queryStart.toInstant(ZoneOffset.UTC);
        Instant latest = queryUntil.atZone(zone).toInstant().isAfter(queryUntil.toInstant(ZoneOffset.UTC))
                ? queryUntil.atZone(zone).toInstant() : queryUntil.toInstant(ZoneOffset.UTC);
        List<EsuplSalesResponse.Sale> selected = new ArrayList<>();
        for (var sale : getSalesForRangeStrict(queryStart, queryUntil.minusSeconds(1))) {
            if (sale.id() <= 0 || sale.eventDate() == null) {
                throw new EsuplSyncException("ESUPL_CHECK_FIELDS", "У чека ID " + sale.id()
                        + " отсутствует корректный ID или event_date.");
            }
            Instant event = sale.eventDate().toInstant();
            if (event.isBefore(earliest) || !event.isBefore(latest)) {
                throw new EsuplSyncException("ESUPL_DATE_RANGE", "Чек ID " + sale.id()
                        + " находится вне расширенного периода выборки. event_date: " + sale.eventDate()
                        + ". Запрошены календарные даты: " + queryStart.toLocalDate() + " — "
                        + queryUntil.minusDays(1).toLocalDate() + ".");
            }
            if (!event.isBefore(from) && event.isBefore(until)) selected.add(sale);
        }
        return selected;
    }

    private List<EsuplSalesResponse.Sale> getSalesForRange(LocalDateTime start, LocalDateTime end, boolean strict) {
        List<EsuplSalesResponse.Sale> result = new ArrayList<>();
        Set<Long> seenIds = new HashSet<>();

        int page = 1;
        int perPage = 50;

        while (true) {
            int currentPage = page;

            EsuplSalesResponse response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/teams/{teamId}/sales")
                            .queryParam("include", INCLUDE)
                            .queryParam("event_date[start]", API_DATE_FORMAT.format(start))
                            .queryParam("event_date[end]", API_DATE_FORMAT.format(end))
                            .queryParam("per_page", perPage)
                            .queryParam("page", currentPage)
                            .queryParam("sort", "desc.event_date")
                            .build(teamId)
                    )
                    .retrieve()
                    .onStatus(
                            status -> status.isError(),
                            clientResponse -> strict
                                    ? clientResponse.releaseBody().thenReturn(new EsuplSyncException(
                                            "ESUPL_HTTP_" + clientResponse.statusCode().value(),
                                            "ESUPL ответил HTTP " + clientResponse.statusCode().value()
                                                    + ". Период: " + start + " — " + end + "; страница " + currentPage + "."))
                                    : clientResponse.bodyToMono(String.class)
                                    .map(body -> new RuntimeException(
                                            "ESUPL API error: HTTP "
                                                    + clientResponse.statusCode()
                                                    + " body: "
                                                    + body
                                    ))
                    )
                    .bodyToMono(EsuplSalesResponse.class)
                    .block(Duration.ofSeconds(30));

            if (strict && (response == null || response.data() == null)) {
                throw new EsuplSyncException("ESUPL_NO_DATA", "В ответе ESUPL нет списка чеков. Период: "
                        + start + " — " + end + "; страница " + currentPage + ".");
            }
            if (response == null || response.data() == null || response.data().isEmpty()) {
                break;
            }

            if (strict) {
                for (EsuplSalesResponse.Sale sale : response.data()) {
                    if (sale == null || sale.id() == null) {
                        throw new EsuplSyncException("ESUPL_CHECK_ID", "ESUPL вернул чек без ID. Период: "
                                + start + " — " + end + "; страница " + currentPage + ".");
                    }
                    if (!seenIds.add(sale.id())) {
                        throw new EsuplSyncException("ESUPL_DUPLICATE", "ESUPL повторно вернул чек ID " + sale.id()
                                + ". Период: " + start + " — " + end + "; страница " + currentPage + ".");
                    }
                }
            }
            result.addAll(response.data());

            if (response.data().size() < perPage) {
                break;
            }

            page++;
        }

        return result;
    }

    public List<EsuplSalesResponse.Sale> getSalesForBusinessDate(LocalDate businessDate) {
        LocalDateTime start = businessDate.atTime(3, 0, 0);
        LocalDateTime end = businessDate.plusDays(1).atTime(2, 59, 59);

        return getSalesForRange(start, end);
    }
}
