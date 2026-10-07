package com.baros.fund;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** One durable ledger. Money is stored as integer kopecks; every write is transactional. */
@Service
@Profile("dvoyka")
public class FundLedger {
    public record Actor(long id, String name) {
        public Actor {
            name = clean(name, 80);
        }
        public static Actor system() { return new Actor(0, "ESUPL · автоматически"); }
    }
    public record Entry(long id, long cents, String kind, String note, Actor actor, Instant createdAt) {}
    public record Snapshot(Long balance, LocalDate startDate, String lastSync, String throughDate) {}
    public record SaleAmount(long cents, LocalDate businessDate) {}
    public record SyncResult(long delta, int changes, long balance) {}
    public static class InputException extends IllegalArgumentException {
        public InputException(String message) { super(message); }
    }

    private final String url;

    public FundLedger(@Value("${fund.database}") String filename) throws Exception {
        Path path = Path.of(filename).toAbsolutePath();
        Files.createDirectories(path.getParent());
        url = "jdbc:sqlite:" + path;
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("CREATE TABLE IF NOT EXISTS fund_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS fund_entries (
                      id INTEGER PRIMARY KEY AUTOINCREMENT, cents INTEGER NOT NULL,
                      kind TEXT NOT NULL, note TEXT NOT NULL, actor_id INTEGER NOT NULL,
                      actor_name TEXT NOT NULL, created_at TEXT NOT NULL, source_key TEXT UNIQUE NOT NULL)
                    """);
            s.execute("CREATE UNIQUE INDEX IF NOT EXISTS one_opening ON fund_entries(kind) WHERE kind='OPENING'");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS fund_sales (
                      sale_id INTEGER PRIMARY KEY, cents INTEGER NOT NULL, business_date TEXT NOT NULL)
                    """);
        }
    }

    private Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(url);
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA busy_timeout=10000");
            s.execute("PRAGMA synchronous=FULL");
        }
        return c;
    }

    @FunctionalInterface
    private interface Work<T> { T run(Connection c) throws SQLException; }

    private synchronized <T> T transaction(Work<T> work) {
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Не удалось сохранить или прочитать журнал «Двойки»", e);
        }
    }

    public Snapshot snapshot() {
        return transaction(c -> {
            String start = meta(c, "start_date");
            return new Snapshot(start == null ? null : balance(c),
                    start == null ? null : LocalDate.parse(start), meta(c, "last_sync"), meta(c, "through_date"));
        });
    }

    public long opening(long updateId, long cents, LocalDate start, Actor actor) {
        Objects.requireNonNull(start);
        return transaction(c -> {
            if (hasSource(c, "tg:" + updateId)) return balance(c);
            if (meta(c, "start_date") != null) {
                throw new InputException("Начальный остаток уже установлен. Для исправления используй поступление или расход с пояснением.");
            }
            addEntry(c, cents, "OPENING", "Начальный остаток перед барным днём " + start, actor, "tg:" + updateId);
            putMeta(c, "start_date", start.toString());
            return balance(c);
        });
    }

    public long manual(long updateId, long cents, String note, Actor actor) {
        if (cents == 0) throw new InputException("Сумма должна быть больше нуля.");
        if (note == null || note.isBlank()) throw new InputException("Добавь пояснение к операции.");
        if (note.length() > 140) throw new InputException("Пояснение должно быть не длиннее 140 символов.");
        return transaction(c -> {
            requireOpening(c);
            if (!hasSource(c, "tg:" + updateId)) {
                addEntry(c, cents, cents > 0 ? "INCOME" : "EXPENSE", note, actor, "tg:" + updateId);
            }
            return balance(c);
        });
    }

    /** Apply an entire successfully fetched ESUPL snapshot atomically, including reversals. */
    public SyncResult reconcile(Map<Long, SaleAmount> incoming, LocalDate expectedStart,
                                LocalDate through, Actor actor) {
        return transaction(c -> {
            requireOpening(c);
            if (!expectedStart.toString().equals(meta(c, "start_date"))) {
                throw new IllegalStateException("Дата отсчёта изменилась во время синхронизации");
            }
            Map<Long, SaleAmount> previous = new HashMap<>();
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM fund_sales")) {
                while (r.next()) previous.put(r.getLong("sale_id"),
                        new SaleAmount(r.getLong("cents"), LocalDate.parse(r.getString("business_date"))));
            }
            Set<Long> ids = new TreeSet<>(previous.keySet());
            ids.addAll(incoming.keySet());
            long delta = 0;
            int changes = 0;
            String syncId = UUID.randomUUID().toString();
            for (long id : ids) {
                SaleAmount old = previous.get(id);
                SaleAmount current = incoming.get(id);
                // Never reverse a previously imported day outside this scan's coverage.
                if (current == null && old.businessDate().isAfter(through)) continue;
                if (current != null && (current.businessDate().isBefore(expectedStart)
                        || current.businessDate().isAfter(through) || current.cents() < 0)) {
                    throw new IllegalStateException("ESUPL snapshot is outside the requested interval");
                }
                long difference = Math.subtractExact(current == null ? 0 : current.cents(), old == null ? 0 : old.cents());
                if (difference != 0) {
                    LocalDate day = current == null ? old.businessDate() : current.businessDate();
                    addEntry(c, difference, "ESUPL", "Сверка ESUPL · чек " + id + " · " + day,
                            actor, "esupl:" + syncId + ":" + id);
                    delta = Math.addExact(delta, difference);
                    changes++;
                }
                if (current == null) {
                    execute(c, "DELETE FROM fund_sales WHERE sale_id=?", id);
                } else {
                    execute(c, "INSERT INTO fund_sales VALUES (?,?,?) ON CONFLICT(sale_id) DO UPDATE SET cents=excluded.cents, business_date=excluded.business_date",
                            id, current.cents(), current.businessDate().toString());
                }
            }
            putMeta(c, "last_sync", Instant.now().toString());
            putMeta(c, "through_date", through.toString());
            return new SyncResult(delta, changes, balance(c));
        });
    }

    public List<Entry> history() {
        return transaction(c -> {
            List<Entry> entries = new ArrayList<>();
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM fund_entries ORDER BY id DESC LIMIT 10")) {
                while (r.next()) entries.add(new Entry(r.getLong("id"), r.getLong("cents"), r.getString("kind"),
                        r.getString("note"), new Actor(r.getLong("actor_id"), r.getString("actor_name")),
                        Instant.parse(r.getString("created_at"))));
            }
            return entries;
        });
    }

    public long offset() {
        return transaction(c -> {
            String value = meta(c, "telegram_offset");
            return value == null ? 0L : Long.parseLong(value);
        });
    }

    public void acknowledge(long updateId) {
        transaction(c -> {
            String value = meta(c, "telegram_offset");
            long offset = value == null ? 0 : Long.parseLong(value);
            putMeta(c, "telegram_offset", Long.toString(Math.max(offset, updateId + 1)));
            return null;
        });
    }

    private void requireOpening(Connection c) throws SQLException {
        if (meta(c, "start_date") == null) throw new InputException("Сначала установи начальный остаток: /opening СУММА ДД.ММ.ГГГГ");
    }

    private long balance(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COALESCE(SUM(cents),0) FROM fund_entries")) {
            return r.next() ? r.getLong(1) : 0;
        }
    }

    private boolean hasSource(Connection c, String key) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT 1 FROM fund_entries WHERE source_key=?")) {
            p.setString(1, key);
            try (ResultSet r = p.executeQuery()) { return r.next(); }
        }
    }

    private String meta(Connection c, String key) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT value FROM fund_meta WHERE key=?")) {
            p.setString(1, key);
            try (ResultSet r = p.executeQuery()) { return r.next() ? r.getString(1) : null; }
        }
    }

    private void putMeta(Connection c, String key, String value) throws SQLException {
        execute(c, "INSERT INTO fund_meta VALUES (?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", key, value);
    }

    private void addEntry(Connection c, long cents, String kind, String note, Actor actor, String source) throws SQLException {
        execute(c, "INSERT INTO fund_entries(cents,kind,note,actor_id,actor_name,created_at,source_key) VALUES (?,?,?,?,?,?,?)",
                cents, kind, clean(note, 200), actor.id(), actor.name(), Instant.now().toString(), source);
    }

    private void execute(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) p.setObject(i + 1, values[i]);
            p.executeUpdate();
        }
    }

    public static long parseMoney(String value, boolean signed) {
        String pattern = signed ? "-?\\d{1,9}([.,]\\d{1,2})?" : "\\d{1,9}([.,]\\d{1,2})?";
        if (!value.matches(pattern)) throw new InputException("Укажи сумму числом, до двух знаков после запятой. Например: 25,50");
        return new BigDecimal(value.replace(',', '.')).movePointRight(2).longValueExact();
    }

    public static String money(long cents) {
        return BigDecimal.valueOf(cents, 2).toPlainString().replace('.', ',') + " BYN";
    }

    public static String clean(String value, int max) {
        String text = value == null ? "" : value.replaceAll("[\\p{Cntrl}]", " ").trim();
        return text.substring(0, Math.min(max, text.length()));
    }
}
