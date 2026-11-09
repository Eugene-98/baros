package com.baros.reservations;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;

@Component
@Profile("reservations")
public record ReservationSettings(String database, Set<Long> owners, long staffChat, boolean testMode,
                                  int openingHour, int closingHour, int duration, Map<String, Integer> tables) {
    public ReservationSettings {
        owners = Set.copyOf(owners);
        tables = Collections.unmodifiableMap(new LinkedHashMap<>(tables));
        if (openingHour < 0 || openingHour > 23 || closingHour < 0 || closingHour > 23
                || openingHour == closingHour || duration < 30 || duration > 360 || duration % 30 != 0
                || tables.isEmpty()) throw new IllegalArgumentException("Invalid reservation hours, duration or tables");
    }

    @Autowired
    public ReservationSettings(
            @Value("${reservations.database}") String database,
            @Value("${reservations.owner-user-ids}") String owners,
            @Value("${reservations.staff-chat-id}") long staffChat,
            @Value("${reservations.test-mode}") boolean testMode,
            @Value("${reservations.opening-hour}") int openingHour,
            @Value("${reservations.closing-hour}") int closingHour,
            @Value("${reservations.duration-minutes}") int duration,
            @Value("${reservations.tables}") String tables) {
        this(database, parseOwners(owners), staffChat, testMode, openingHour, closingHour, duration, parseTables(tables));
    }

    public static final ZoneId ZONE = ZoneId.of("Europe/Minsk");
    static Set<Long> parseOwners(String value) {
        var result = new HashSet<Long>();
        if (!value.isBlank()) for (String id : value.split(",")) {
            long parsed = Long.parseLong(id.trim());
            if (parsed <= 0) throw new IllegalArgumentException("Owner IDs must be positive");
            result.add(parsed);
        }
        return result;
    }
    static Map<String, Integer> parseTables(String value) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String item : value.split(",")) {
            String[] parts = item.trim().split(":", -1);
            if (parts.length != 2 || !parts[0].matches("[A-Za-z0-9_-]{1,20}"))
                throw new IllegalArgumentException("Use table-id:capacity,table-id:capacity");
            int capacity = Integer.parseInt(parts[1]);
            if (capacity < 1 || capacity > 50 || result.put(parts[0], capacity) != null)
                throw new IllegalArgumentException("Invalid or duplicate table");
        }
        return result;
    }
    public ZonedDateTime open(LocalDate day) { return day.atTime(openingHour, 0).atZone(ZONE); }
    public ZonedDateTime close(LocalDate day) {
        return (closingHour <= openingHour ? day.plusDays(1) : day).atTime(closingHour, 0).atZone(ZONE);
    }
    public LocalDate businessDay(Instant now) {
        ZonedDateTime local = now.atZone(ZONE);
        return closingHour <= openingHour && local.getHour() < closingHour
                ? local.toLocalDate().minusDays(1) : local.toLocalDate();
    }
    public void validateSlot(LocalDate day, long starts, Instant now) {
        ZonedDateTime start = Instant.ofEpochSecond(starts).atZone(ZONE);
        if (day.isBefore(businessDay(now)) || day.isAfter(now.atZone(ZONE).toLocalDate().plusDays(30))
                || starts <= now.getEpochSecond() || start.isBefore(open(day))
                || start.plusMinutes(duration).isAfter(close(day))
                || start.getMinute() % 30 != 0 || start.getSecond() != 0)
            throw new ReservationStore.Input("Это время уже недоступно. Выбери дату и время заново.");
    }
}
