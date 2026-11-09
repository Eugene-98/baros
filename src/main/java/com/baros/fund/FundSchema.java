package com.baros.fund;

import java.sql.*;

/** Additive migrations: financial entries and original authors are never rewritten. */
final class FundSchema {
    private FundSchema() {}

    static void initialize(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("""
                    CREATE TABLE IF NOT EXISTS fund_users (
                      user_id INTEGER PRIMARY KEY, name TEXT NOT NULL, role TEXT NOT NULL,
                      version INTEGER NOT NULL DEFAULT 1, requested_at TEXT)
                    """);
            s.execute("""
                    CREATE TABLE IF NOT EXISTS fund_access_audit (
                      update_id INTEGER PRIMARY KEY, actor_id INTEGER NOT NULL, target_id INTEGER NOT NULL,
                      old_role TEXT, new_role TEXT NOT NULL, created_at TEXT NOT NULL, reply TEXT NOT NULL)
                    """);
            s.execute("""
                    CREATE TABLE IF NOT EXISTS fund_notifications (
                      id INTEGER PRIMARY KEY AUTOINCREMENT, source TEXT UNIQUE NOT NULL,
                      chat_id INTEGER NOT NULL, text TEXT NOT NULL, next_attempt INTEGER NOT NULL DEFAULT 0)
                    """);
            s.execute("""
                    CREATE TABLE IF NOT EXISTS fund_categories (
                      id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL COLLATE NOCASE UNIQUE,
                      active INTEGER NOT NULL DEFAULT 1)
                    """);
            s.execute("""
                    CREATE TABLE IF NOT EXISTS fund_entry_categories (
                      entry_id INTEGER PRIMARY KEY, category_id INTEGER NOT NULL, version INTEGER NOT NULL)
                    """);
            s.execute("""
                    CREATE TABLE IF NOT EXISTS fund_template_categories (
                      template TEXT PRIMARY KEY, category_id INTEGER NOT NULL, version INTEGER NOT NULL DEFAULT 1)
                    """);
            s.execute("""
                    CREATE TABLE IF NOT EXISTS fund_category_audit (
                      update_id INTEGER PRIMARY KEY, actor_id INTEGER NOT NULL, subject TEXT NOT NULL,
                      old_value TEXT, new_value TEXT, created_at TEXT NOT NULL, reply TEXT NOT NULL)
                    """);
            s.execute("""
                    CREATE TABLE IF NOT EXISTS fund_ui (
                      chat_id INTEGER NOT NULL, user_id INTEGER NOT NULL, mode TEXT NOT NULL,
                      value INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(chat_id,user_id))
                    """);
            boolean scope = false;
            try (ResultSet r = s.executeQuery("PRAGMA table_info(fund_history_views)")) {
                while (r.next()) if (r.getString("name").equals("scope_user_id")) scope = true;
            }
            if (!scope) s.execute("ALTER TABLE fund_history_views ADD COLUMN scope_user_id INTEGER");
            // Seed only once. Archived/renamed categories and edited defaults survive restarts.
            try (ResultSet r = s.executeQuery("SELECT value FROM fund_meta WHERE key='categories_initialized'")) {
                if (r.next()) return;
            }
            c.setAutoCommit(false);
            try {
                for (String name : new String[]{"Алкоголь и напитки", "Продукты и ингредиенты", "Хозтовары и расходники",
                        "Оборудование и ремонт", "Выплаты сотрудникам", "Доставка и транспорт", "Прочее"}) {
                    try (PreparedStatement p = c.prepareStatement("INSERT OR IGNORE INTO fund_categories(name) VALUES (?)")) {
                        p.setString(1, name); p.executeUpdate();
                    }
                }
                for (String template : new String[]{"Хозяюшка", "Рынок", "Бар Закупка"}) {
                    try (PreparedStatement p = c.prepareStatement("INSERT OR IGNORE INTO fund_template_categories(template,category_id) VALUES (?,0)")) {
                        p.setString(1, template); p.executeUpdate();
                    }
                }
                s.execute("INSERT INTO fund_meta VALUES ('categories_initialized','1')");
                c.commit();
            } catch (SQLException e) { c.rollback(); throw e; }
            finally { c.setAutoCommit(true); }
        }
    }
}
