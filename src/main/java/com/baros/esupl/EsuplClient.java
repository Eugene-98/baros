package com.baros.esupl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

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
                .build();
    }

    public List<EsuplSalesResponse.Sale> getSalesForBusinessDate(LocalDate businessDate) {
        LocalDateTime start = businessDate.atTime(3, 0, 0);
        LocalDateTime end = businessDate.plusDays(1).atTime(2, 59, 59);

        List<EsuplSalesResponse.Sale> result = new ArrayList<>();

        int page = 1;
        int perPage = 100;
        int currentPage = page;
        while (true) {
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
                            clientResponse -> clientResponse.bodyToMono(String.class)
                                    .map(body -> new RuntimeException(
                                            "ESUPL API error: HTTP "
                                                    + clientResponse.statusCode()
                                                    + " body: "
                                                    + body
                                    ))
                    )
                    .bodyToMono(EsuplSalesResponse.class)
                    .block(Duration.ofSeconds(30));

            if (response == null || response.data() == null || response.data().isEmpty()) {
                break;
            }

            result.addAll(response.data());

            if (response.data().size() < perPage) {
                break;
            }

            page++;
        }


        return result;
    }
}
