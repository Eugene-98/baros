package com.baros.reservations;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** One transaction contains the state change, replies, notifications and Telegram offset. */
@Component
@Profile("reservations")
public class ReservationStore {
    static class Input extends RuntimeException { Input(String message) { super(message); } }
    @FunctionalInterface interface Work<T> { T run(Connection connection) throws Exception; }
    static final JsonMapper JSON = JsonMapper.builder().build();
    private final String url;

    public ReservationStore(ReservationSettings settings) throws Exception {
        Path path = Path.of(settings.database()).toAbsolutePath();
        Files.createDirectories(path.getParent());
        url = "jdbc:sqlite:" + path;
        try (Connection c = connection(); Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("CREATE TABLE IF NOT EXISTS reservation_meta (key TEXT PRIMARY KEY, value INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS reservation_staff (user_id INTEGER PRIMARY KEY, name TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS reservation_access_requests (user_id INTEGER PRIMARY KEY, name TEXT NOT NULL, requested_at INTEGER NOT NULL)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS reservations (
                      id INTEGER PRIMARY KEY AUTOINCREMENT, guest_id INTEGER, created_by INTEGER NOT NULL,
                      business_day TEXT NOT NULL, starts INTEGER NOT NULL, ends INTEGER NOT NULL,
                      party INTEGER NOT NULL CHECK(party BETWEEN 1 AND 20), name TEXT NOT NULL,
                      phone TEXT NOT NULL, note TEXT NOT NULL, status TEXT NOT NULL,
                      table_id TEXT, version INTEGER NOT NULL DEFAULT 1, reminded INTEGER NOT NULL DEFAULT 0,
                      created_at INTEGER NOT NULL)
                    """);
            s.execute("CREATE INDEX IF NOT EXISTS reservation_overlap ON reservations(status,table_id,starts,ends)");
            s.execute("CREATE INDEX IF NOT EXISTS reservation_guest ON reservations(guest_id,starts)");
            s.execute("CREATE TABLE IF NOT EXISTS reservation_sessions (user_id INTEGER PRIMARY KEY, value TEXT NOT NULL)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS reservation_outbox (
                      id INTEGER PRIMARY KEY AUTOINCREMENT, method TEXT NOT NULL, body TEXT NOT NULL,
                      next_attempt INTEGER NOT NULL DEFAULT 0, attempts INTEGER NOT NULL DEFAULT 0,
                      booking_id INTEGER, booking_version INTEGER, expires_at INTEGER NOT NULL DEFAULT 0)
                    """);
            s.execute("""
                    CREATE TABLE IF NOT EXISTS reservation_audit (
                      id INTEGER PRIMARY KEY AUTOINCREMENT, update_id INTEGER NOT NULL,
                      actor_id INTEGER NOT NULL, booking_id INTEGER, action TEXT NOT NULL, created_at INTEGER NOT NULL)
                    """);
        }
    }
    private Connection connection() throws SQLException {
        Connection c = DriverManager.getConnection(url);
        try (Statement s = c.createStatement()) { s.execute("PRAGMA busy_timeout=10000"); }
        return c;
    }
    synchronized <T> T transaction(Work<T> work) {
        try (Connection c = connection(); Statement s = c.createStatement()) {
            s.execute("BEGIN IMMEDIATE");
            try {
                T result = work.run(c);
                s.execute("COMMIT");
                return result;
            } catch (Exception e) {
                s.execute("ROLLBACK");
                if (e instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("Reservation transaction failed", e);
            }
        } catch (SQLException e) { throw new IllegalStateException("Reservation database unavailable", e); }
    }
    static int execute(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(sql)) {
            bind(p, args); return p.executeUpdate();
        }
    }
    static List<Map<String, Object>> rows(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(sql)) {
            bind(p, args);
            try (ResultSet rs = p.executeQuery()) {
                List<Map<String, Object>> list = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++)
                        row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                    list.add(row);
                }
                return list;
            }
        }
    }
    private static void bind(PreparedStatement p, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]);
    }
    static Map<String, Object> one(Connection c, String sql, Object... args) throws SQLException {
        var found = rows(c, sql, args); return found.isEmpty() ? null : found.getFirst();
    }
    static long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    static String str(Map<String, Object> row, String key) { return Objects.toString(row.get(key), ""); }
    static void queue(Connection c, String method, Map<String, Object> body) throws SQLException {
        execute(c, "INSERT INTO reservation_outbox(method,body) VALUES (?,?)", method, JSON.writeValueAsString(body));
    }
    static void guardLastMessage(Connection c, Map<String, Object> booking, long expiresAt) throws SQLException {
        execute(c, "UPDATE reservation_outbox SET booking_id=?,booking_version=?,expires_at=? WHERE id=last_insert_rowid()",
                number(booking, "id"), number(booking, "version"), expiresAt);
    }
    boolean current(Map<String, Object> notification, long now) {
        if (number(notification, "expires_at") != 0 && number(notification, "expires_at") <= now) return false;
        if (notification.get("booking_id") == null) return true;
        return transaction(c -> {
            var booking = one(c, "SELECT version FROM reservations WHERE id=?", number(notification, "booking_id"));
            return booking != null && number(booking, "version") == number(notification, "booking_version");
        });
    }
    long offset() {
        return transaction(c -> {
            var row = one(c, "SELECT value FROM reservation_meta WHERE key='offset'");
            return row == null ? 0L : number(row, "value");
        });
    }
    List<Map<String, Object>> outbox(long now) {
        return transaction(c -> rows(c, "SELECT * FROM reservation_outbox WHERE next_attempt<=? ORDER BY id LIMIT 20", now));
    }
    void delivered(long id) { transaction(c -> execute(c, "DELETE FROM reservation_outbox WHERE id=?", id)); }
    void retry(long id, long when) {
        transaction(c -> execute(c, "UPDATE reservation_outbox SET next_attempt=?, attempts=attempts+1 WHERE id=?", when, id));
    }
    static boolean available(Connection c, String table, long starts, long ends, long except) throws SQLException {
        return one(c, """
                SELECT id FROM reservations WHERE status='CONFIRMED' AND table_id=?
                AND starts<? AND ends>? AND id<>? LIMIT 1
                """, table, ends, starts, except) == null;
    }
}
