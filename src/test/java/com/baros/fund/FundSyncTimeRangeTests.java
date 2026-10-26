package com.baros.fund;

import com.baros.esupl.EsuplClient;
import com.baros.esupl.EsuplSyncException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;

class FundSyncTimeRangeTests {
    @TempDir Path temp;
    private static final ZoneId MINSK = ZoneId.of("Europe/Minsk");
    private static final FundLedger.Actor AUTHOR = new FundLedger.Actor(123, "Автор");
    private static final DateTimeFormatter QUERY_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private HttpServer server;
    private EsuplClient client;
    private FundLedger ledger;
    private final List<Check> checks = new ArrayList<>();
    private final List<Map<String, String>> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger failPage = new AtomicInteger();

    enum FilterMode {
        UTC(ZoneOffset.UTC, false), LOCAL(MINSK, false), UTC_DATE_ONLY(ZoneOffset.UTC, true), LOCAL_DATE_ONLY(MINSK, true);
        final ZoneId zone;
        final boolean dateOnly;
        FilterMode(ZoneId zone, boolean dateOnly) { this.zone = zone; this.dateOnly = dateOnly; }
    }

    private record Check(long id, Instant time, boolean paid, int amount) {
        String json() {
            return "{\"id\":" + id + ",\"event_date\":\"" + time + "\",\"is_paid\":" + paid
                    + ",\"is_deleted\":false,\"total_discount\":" + amount
                    + ",\"total_discounts\":[{\"id\":1,\"name\":\"Порча\",\"amount\":" + amount + "}]}";
        }
    }

    private void start(FilterMode mode) throws Exception {
        checks.add(new Check(1, Instant.parse("2026-10-21T23:59:59Z"), true, 90)); // Before opening date.
        checks.add(new Check(2, Instant.parse("2026-10-22T00:00:00Z"), true, 10)); // Exactly 03:00 Minsk.
        checks.add(new Check(3, Instant.parse("2026-10-22T23:59:59.999Z"), true, 30)); // Last millisecond of shift.
        checks.add(new Check(4, Instant.parse("2026-10-23T00:00:00Z"), true, 40)); // Next shift boundary.
        checks.add(new Check(29627691, Instant.parse("2026-10-23T00:08:33Z"), true, 20)); // Reported failure.
        // The first page contains only neighbouring-day checks: filtering it must not stop pagination.
        for (int i = 0; i < 55; i++) checks.add(new Check(100 + i, Instant.parse("2026-10-23T09:00:00Z").plusSeconds(i), false, 1));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/teams/1/sales", exchange -> {
            var query = Arrays.stream(exchange.getRequestURI().getRawQuery().split("&"))
                    .map(p -> p.split("=", 2)).collect(Collectors.toMap(
                            p -> URLDecoder.decode(p[0], StandardCharsets.UTF_8),
                            p -> URLDecoder.decode(p[1], StandardCharsets.UTF_8)));
            requests.add(query);
            int page = Integer.parseInt(query.get("page"));
            int perPage = Integer.parseInt(query.get("per_page"));
            LocalDateTime start = LocalDateTime.parse(query.get("event_date[start]"), QUERY_DATE);
            LocalDateTime end = LocalDateTime.parse(query.get("event_date[end]"), QUERY_DATE);
            Instant from = (mode.dateOnly ? start.toLocalDate().atStartOfDay() : start).atZone(mode.zone).toInstant();
            Instant until = (mode.dateOnly ? end.toLocalDate().plusDays(1).atStartOfDay() : end.plusSeconds(1)).atZone(mode.zone).toInstant();
            String body = "{\"data\":[" + checks.stream()
                    .filter(c -> !c.time().isBefore(from) && c.time().isBefore(until))
                    .sorted(Comparator.comparing(Check::time).reversed()).skip((long) (page - 1) * perPage).limit(perPage)
                    .map(Check::json).collect(Collectors.joining(",")) + "]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(page == failPage.get() ? 503 : 200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        client = new EsuplClient("http://127.0.0.1:" + server.getAddress().getPort(), "test", 1);
        ledger = new FundLedger(temp.resolve("fund.sqlite").toString());
        ledger.opening(1, 100_000, LocalDate.of(2026, 10, 22), AUTHOR);
        ledger.manual(2, -500, "Расход до сверки", AUTHOR);
    }

    private FundSyncService syncAt(String instant) {
        return new FundSyncService(ledger, client, Clock.fixed(Instant.parse(instant), MINSK), 3, "Порча");
    }

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @ParameterizedTest
    @EnumSource(FilterMode.class)
    void neighbouringChecksDoNotBlockSyncOrGetCountedEarlyOrTwice(FilterMode mode) throws Exception {
        start(mode);
        var today = syncAt("2026-10-23T12:00:00Z");
        assertThat(today.sync(AUTHOR).delta()).isEqualTo(4000);
        assertThat(ledger.snapshot().balance()).isEqualTo(103_500);
        assertThat(ledger.snapshot().throughDate()).isEqualTo("2026-10-22");
        assertThat(requests).hasSize(2);
        assertThat(requests.getFirst()).containsEntry("event_date[start]", "2026-10-22 00:00:00")
                .containsEntry("event_date[end]", "2026-10-23 23:59:59");
        assertThat(requests.getLast()).containsEntry("page", "2");
        assertThat(today.sync(AUTHOR).delta()).isZero();
        assertThat(ledger.history()).noneMatch(e -> e.note().contains("29627691"));

        var tomorrow = syncAt("2026-10-24T00:00:00Z");
        assertThat(tomorrow.sync(AUTHOR).delta()).isEqualTo(6000);
        assertThat(ledger.snapshot().balance()).isEqualTo(109_500);
        assertThat(ledger.snapshot().throughDate()).isEqualTo("2026-10-23");
        assertThat(tomorrow.sync(AUTHOR).delta()).isZero();
        assertThat(ledger.history().stream().filter(e -> e.note().contains("29627691"))).hasSize(1);
    }

    @Test void failedLaterPageKeepsPreviousImportAndManualEntry() throws Exception {
        start(FilterMode.UTC);
        var sync = syncAt("2026-10-23T12:00:00Z");
        sync.sync(AUTHOR);
        var before = ledger.snapshot();
        var history = ledger.history();
        failPage.set(2);
        assertThatThrownBy(() -> sync.sync(AUTHOR)).isInstanceOfSatisfying(EsuplSyncException.class,
                e -> assertThat(e.diagnostic()).contains("ESUPL_HTTP_503", "страница 2"));
        assertThat(ledger.snapshot()).isEqualTo(before);
        assertThat(ledger.history()).isEqualTo(history);
    }
}
