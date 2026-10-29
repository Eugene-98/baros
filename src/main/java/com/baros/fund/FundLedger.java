package com.baros.fund;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
    public record Snapshot(Long balance, LocalDate startDate, String lastSync, String throughDate, int startHour) {}
    public record SaleAmount(long cents, LocalDate businessDate) {}
    public record SyncResult(long delta, int changes, long balance) {}
    public record Draft(boolean expense, String note) {}
    public record HistoryView(String period, Instant from, Instant until, long maxId, int page, long updateId) {}
    public record HistoryPage(HistoryView view, List<Entry> entries, long total, int pages) {}
    public static final int HISTORY_PAGE_SIZE = 7;
    private static final DateTimeFormatter UTC_SECOND = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    public static class InputException extends IllegalArgumentException {
        public InputException(String message) { super(message); }
    }

    private final String url;
    private final int dayStartHour;

    @Autowired
    public FundLedger(@Value("${fund.database}") String filename,
                      @Value("${bar.day-start-hour}") int dayStartHour) throws Exception {
        if (dayStartHour < 0 || dayStartHour > 23) throw new IllegalArgumentException("Invalid bar-day hour");
        this.dayStartHour = dayStartHour;
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
            s.execute("CREATE INDEX IF NOT EXISTS entry_time ON fund_entries(substr(created_at,1,19),id)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS fund_history_views (
                      chat_id INTEGER NOT NULL, user_id INTEGER NOT NULL, period TEXT NOT NULL,
                      from_at TEXT, until_at TEXT, max_id INTEGER NOT NULL, page INTEGER NOT NULL,
                      update_id INTEGER NOT NULL, PRIMARY KEY(chat_id,user_id))
                    """);
            s.execute("""
                    CREATE TABLE IF NOT EXISTS fund_drafts (
                      chat_id INTEGER NOT NULL, user_id INTEGER NOT NULL,
                      expense INTEGER NOT NULL, note TEXT,
                      PRIMARY KEY(chat_id, user_id))
                    """);
            s.execute("""
                    CREATE TABLE IF NOT EXISTS fund_sales (
                      sale_id INTEGER PRIMARY KEY, cents INTEGER NOT NULL, business_date TEXT NOT NULL)
                    """);
        }
    }

    FundLedger(String filename) throws Exception {
        this(filename, 3);
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
            String hour = meta(c, "start_hour");
            return new Snapshot(start == null ? null : balance(c),
                    start == null ? null : LocalDate.parse(start), meta(c, "last_sync"), meta(c, "through_date"),
                    hour == null ? (start == null ? dayStartHour : 3) : Integer.parseInt(hour));
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
            putMeta(c, "start_hour", Integer.toString(dayStartHour));
            return balance(c);
        });
    }

    public long manual(long updateId, long cents, String note, Actor actor) {
        return manual(updateId, cents, note, actor, null);
    }

    /** Save the operation and finish the user's input in the same transaction. */
    public long manual(long updateId, long cents, String note, Actor actor, Long chatId) {
        if (cents == 0) throw new InputException("Сумма должна быть больше нуля.");
        if (note == null || note.isBlank()) throw new InputException("Добавь пояснение к операции.");
        if (note.length() > 140) throw new InputException("Пояснение должно быть не длиннее 140 символов.");
        return transaction(c -> {
            requireOpening(c);
            if (!hasSource(c, "tg:" + updateId)) {
                addEntry(c, cents, cents > 0 ? "INCOME" : "EXPENSE", note, actor, "tg:" + updateId);
                if (chatId != null) deleteDraft(c, chatId, actor.id());
            }
            return balance(c);
        });
    }

    public Draft draft(long chatId, long userId) {
        return transaction(c -> {
            try (PreparedStatement p = c.prepareStatement("SELECT expense,note FROM fund_drafts WHERE chat_id=? AND user_id=?")) {
                p.setLong(1, chatId);
                p.setLong(2, userId);
                try (ResultSet r = p.executeQuery()) {
                    return r.next() ? new Draft(r.getBoolean(1), r.getString(2)) : null;
                }
            }
        });
    }

    public void saveDraft(long chatId, long userId, Draft draft) {
        transaction(c -> {
            requireOpening(c);
            execute(c, """
                    INSERT INTO fund_drafts VALUES (?,?,?,?) ON CONFLICT(chat_id,user_id)
                    DO UPDATE SET expense=excluded.expense,note=excluded.note
                    """, chatId, userId, draft.expense(), draft.note());
            return null;
        });
    }

    public void cancelDraft(long chatId, long userId) {
        transaction(c -> { deleteDraft(c, chatId, userId); return null; });
    }

    private void deleteDraft(Connection c, long chatId, long userId) throws SQLException {
        execute(c, "DELETE FROM fund_drafts WHERE chat_id=? AND user_id=?", chatId, userId);
    }

    /** Recover a successful write when Telegram failed before receiving our reply. */
    public Entry manualEntry(long updateId) {
        return transaction(c -> {
            try (PreparedStatement p = c.prepareStatement("SELECT * FROM fund_entries WHERE source_key=? AND kind IN ('INCOME','EXPENSE')")) {
                p.setString(1, "tg:" + updateId);
                try (ResultSet r = p.executeQuery()) {
                    return r.next() ? new Entry(r.getLong("id"), r.getLong("cents"), r.getString("kind"),
                            r.getString("note"), new Actor(r.getLong("actor_id"), r.getString("actor_name")),
                            Instant.parse(r.getString("created_at"))) : null;
                }
            }
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

    public HistoryView historyView(long chatId, long userId) {
        return transaction(c -> historyView(c, chatId, userId));
    }

    private HistoryView historyView(Connection c, long chatId, long userId) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT * FROM fund_history_views WHERE chat_id=? AND user_id=?")) {
            p.setLong(1, chatId);
            p.setLong(2, userId);
            try (ResultSet r = p.executeQuery()) {
                return r.next() ? new HistoryView(r.getString("period"),
                        r.getString("from_at") == null ? null : Instant.parse(r.getString("from_at")),
                        r.getString("until_at") == null ? null : Instant.parse(r.getString("until_at")),
                        r.getLong("max_id"), r.getInt("page"), r.getLong("update_id")) : null;
            }
        }
    }

    public HistoryView openHistory(long chatId, long userId, long updateId, String period, Instant from, Instant until) {
        if (!period.equals("menu") && (from == null || until == null || !from.isBefore(until)
                || from.getNano() != 0 || until.getNano() != 0)) throw new IllegalArgumentException("Invalid history interval");
        return transaction(c -> {
            var old = historyView(c, chatId, userId);
            if (old != null && old.updateId() == updateId) return old;
            long maxId;
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COALESCE(MAX(id),0) FROM fund_entries")) {
                maxId = r.next() ? r.getLong(1) : 0;
            }
            var view = new HistoryView(period, from, until, maxId, 0, updateId);
            saveHistoryView(c, chatId, userId, view);
            deleteDraft(c, chatId, userId);
            return view;
        });
    }

    private void saveHistoryView(Connection c, long chatId, long userId, HistoryView view) throws SQLException {
        execute(c, """
                INSERT INTO fund_history_views VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(chat_id,user_id)
                DO UPDATE SET period=excluded.period,from_at=excluded.from_at,until_at=excluded.until_at,
                  max_id=excluded.max_id,page=excluded.page,update_id=excluded.update_id
                """, chatId, userId, view.period(), view.from() == null ? null : view.from().toString(),
                view.until() == null ? null : view.until().toString(), view.maxId(), view.page(), view.updateId());
    }

    public void closeHistory(long chatId, long userId) {
        transaction(c -> { execute(c, "DELETE FROM fund_history_views WHERE chat_id=? AND user_id=?", chatId, userId); return null; });
    }

    public HistoryView moveHistory(long chatId, long userId, long updateId, int direction) {
        return transaction(c -> {
            var view = historyView(c, chatId, userId);
            if (view == null || view.period().equals("menu")) throw new InputException("Сначала выбери период кнопкой «История».");
            // Retrying an update after a failed Telegram reply must not advance the page twice.
            if (view.updateId() == updateId) return view;
            int pages = historyPages(historyCount(c, view));
            int page = Math.max(0, Math.min(pages - 1, view.page() + direction));
            var moved = new HistoryView(view.period(), view.from(), view.until(), view.maxId(), page, updateId);
            saveHistoryView(c, chatId, userId, moved);
            return moved;
        });
    }

    // Entries store UTC instants. Whole-second prefixes give exact day boundaries
    // even when the timestamps have different numbers of fractional-second digits.
    private static final String HISTORY_WHERE = " WHERE substr(created_at,1,19)>=? AND substr(created_at,1,19)<? AND id<=?";

    private void historyParameters(PreparedStatement p, HistoryView view) throws SQLException {
        p.setString(1, UTC_SECOND.format(view.from()));
        p.setString(2, UTC_SECOND.format(view.until()));
        p.setLong(3, view.maxId());
    }

    private long historyCount(Connection c, HistoryView view) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT COUNT(*) FROM fund_entries" + HISTORY_WHERE)) {
            historyParameters(p, view);
            try (ResultSet r = p.executeQuery()) { return r.next() ? r.getLong(1) : 0; }
        }
    }

    private static int historyPages(long count) {
        return (int) Math.max(1, (count + HISTORY_PAGE_SIZE - 1) / HISTORY_PAGE_SIZE);
    }

    public HistoryPage historyPage(HistoryView view) {
        return transaction(c -> {
            long total = historyCount(c, view);
            List<Entry> entries = new ArrayList<>();
            try (PreparedStatement p = c.prepareStatement("SELECT * FROM fund_entries" + HISTORY_WHERE + " ORDER BY id DESC LIMIT ? OFFSET ?")) {
                historyParameters(p, view);
                p.setInt(4, HISTORY_PAGE_SIZE);
                p.setLong(5, (long) view.page() * HISTORY_PAGE_SIZE);
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) entries.add(new Entry(r.getLong("id"), r.getLong("cents"), r.getString("kind"),
                            r.getString("note"), new Actor(r.getLong("actor_id"), r.getString("actor_name")),
                            Instant.parse(r.getString("created_at"))));
                }
            }
            return new HistoryPage(view, entries, total, historyPages(total));
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
