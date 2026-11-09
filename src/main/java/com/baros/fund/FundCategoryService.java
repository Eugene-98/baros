package com.baros.fund;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.time.Instant;
import java.util.*;

@Service
@Profile("dvoyka")
public class FundCategoryService {
    public static final List<String> TEMPLATES = List.of("Хозяюшка", "Рынок", "Бар Закупка");
    public record Category(long id, String name, boolean active) {}
    public record Expense(long id, long cents, String note, long actorId, long categoryId, int version) {}
    public record Template(String name, long categoryId, int version) {}
    private final FundLedger ledger;
    private final FundAccessService access;

    public FundCategoryService(FundLedger ledger, FundAccessService access) { this.ledger = ledger; this.access = access; }

    public List<Category> categories(boolean onlyActive) {
        return ledger.transaction(c -> {
            List<Category> result = new ArrayList<>();
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM fund_categories "
                    + (onlyActive ? "WHERE active=1 " : "") + "ORDER BY id")) {
                while (r.next()) result.add(new Category(r.getLong("id"), r.getString("name"), r.getBoolean("active")));
            }
            return result;
        });
    }

    public String categoryName(long id) {
        return categories(false).stream().filter(c -> c.id() == id).map(Category::name).findFirst().orElse("Без категории");
    }

    public Expense expense(long id) { return ledger.transaction(c -> expense(c, id)); }

    private Expense expense(Connection c, long id) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("""
                SELECT e.id,e.cents,e.note,e.actor_id,COALESCE(ec.category_id,0),COALESCE(ec.version,0)
                FROM fund_entries e LEFT JOIN fund_entry_categories ec ON ec.entry_id=e.id WHERE e.id=? AND e.kind='EXPENSE'
                """)) {
            p.setLong(1, id);
            try (ResultSet r = p.executeQuery()) { return r.next() ? readExpense(r) : null; }
        }
    }

    private Expense readExpense(ResultSet r) throws SQLException {
        return new Expense(r.getLong(1), r.getLong(2), r.getString(3), r.getLong(4), r.getLong(5), r.getInt(6));
    }

    public void requireExpenseAccess(long userId, Expense expense) {
        ledger.transaction(c -> { requireExpenseAccess(c, userId, expense); return null; });
    }

    private void requireExpenseAccess(Connection c, long userId, Expense expense) throws SQLException {
        var m = access.member(c, userId);
        if (m == null || !m.active() || expense == null || (!m.manages() && expense.actorId() != userId))
            throw new FundLedger.InputException("Расход не найден или недоступен для изменения.");
    }

    public List<Expense> uncategorized(long userId, long before) {
        return ledger.transaction(c -> {
            var m = access.member(c, userId);
            if (m == null || !m.active()) throw new FundLedger.InputException("Нет доступа.");
            List<Expense> result = new ArrayList<>();
            try (PreparedStatement p = c.prepareStatement("""
                    SELECT e.id,e.cents,e.note,e.actor_id,COALESCE(ec.category_id,0),COALESCE(ec.version,0)
                    FROM fund_entries e LEFT JOIN fund_entry_categories ec ON ec.entry_id=e.id
                    WHERE e.kind='EXPENSE' AND COALESCE(ec.category_id,0)=0 AND e.id<?
                    """ + (m.manages() ? "" : " AND e.actor_id=?") + " ORDER BY e.id DESC LIMIT 8")) {
                p.setLong(1, before <= 0 ? Long.MAX_VALUE : before);
                if (!m.manages()) p.setLong(2, userId);
                try (ResultSet r = p.executeQuery()) { while (r.next()) result.add(readExpense(r)); }
            }
            return result;
        });
    }

    public String assign(long updateId, long userId, long expenseId, long categoryId, int version) {
        return ledger.transaction(c -> {
            var e = expense(c, expenseId);
            requireExpenseAccess(c, userId, e);
            String replay = replay(c, updateId);
            if (replay != null) return replay;
            if (e.version() != version) throw new FundLedger.InputException("Категория уже изменена. Открой расход заново.");
            String name = activeCategory(c, categoryId);
            ledger.execute(c, """
                    INSERT INTO fund_entry_categories VALUES (?,?,?) ON CONFLICT(entry_id)
                    DO UPDATE SET category_id=excluded.category_id,version=excluded.version
                    """, expenseId, categoryId, version + 1);
            String reply = "Расход #" + expenseId + " · категория: " + name + ".\nСумма и назначение сохранены.";
            audit(c, updateId, userId, "entry:" + expenseId, Long.toString(e.categoryId()), Long.toString(categoryId), reply);
            return reply;
        });
    }

    public List<Template> templates() {
        return ledger.transaction(c -> {
            List<Template> result = new ArrayList<>();
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT template,category_id,version FROM fund_template_categories ORDER BY template")) {
                while (r.next()) result.add(new Template(r.getString(1), r.getLong(2), r.getInt(3)));
            }
            return result;
        });
    }

    public String setTemplate(long updateId, long userId, int index, long categoryId, int version) {
        if (index < 0 || index >= TEMPLATES.size()) throw new FundLedger.InputException("Шаблон не найден.");
        String template = TEMPLATES.get(index);
        return ledger.transaction(c -> {
            access.requireManager(c, userId);
            String replay = replay(c, updateId);
            if (replay != null) return replay;
            String name = activeCategory(c, categoryId);
            try (PreparedStatement p = c.prepareStatement("SELECT category_id,version FROM fund_template_categories WHERE template=?")) {
                p.setString(1, template);
                try (ResultSet r = p.executeQuery()) {
                    if (!r.next() || r.getInt(2) != version) throw new FundLedger.InputException("Шаблон уже изменён. Открой настройки заново.");
                    String old = r.getString(1);
                    ledger.execute(c, "UPDATE fund_template_categories SET category_id=?,version=version+1 WHERE template=?", categoryId, template);
                    String reply = "Шаблон «" + template + "» → " + name + ".\nПравило применяется только к новым расходам с этим точным назначением.";
                    audit(c, updateId, userId, "template:" + template, old, Long.toString(categoryId), reply);
                    return reply;
                }
            }
        });
    }

    public String saveName(long updateId, long userId, long id, String input) {
        String name = input.strip().replaceAll("[\\p{Zs}\\s]+", " ");
        if (name.isBlank() || name.length() > 48 || !name.equals(FundLedger.clean(name, 48)) || name.startsWith("/")
                || name.equalsIgnoreCase("Без категории")) throw new FundLedger.InputException("Название категории: от 1 до 48 символов; «Без категории» зарезервировано.");
        return ledger.transaction(c -> {
            access.requireManager(c, userId);
            String replay = replay(c, updateId);
            if (replay != null) return replay;
            String old = null;
            int activeCount = 0;
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM fund_categories")) {
                while (r.next()) {
                    if (r.getBoolean("active")) activeCount++;
                    if (r.getLong("id") == id) old = r.getString("name");
                    if (r.getLong("id") != id && r.getString("name").equalsIgnoreCase(name))
                        throw new FundLedger.InputException("Категория с таким названием уже существует, в том числе среди архивных.");
                }
            }
            if (id != 0 && old == null) throw new FundLedger.InputException("Категория не найдена.");
            if (id == 0) {
                if (activeCount >= 20) throw new FundLedger.InputException("Можно использовать до 20 активных категорий. Сначала убери ненужную в архив.");
                ledger.execute(c, "INSERT INTO fund_categories(name) VALUES (?)", name);
            } else ledger.execute(c, "UPDATE fund_categories SET name=? WHERE id=?", name, id);
            String reply = "Категория «" + name + "» " + (id == 0 ? "добавлена" : "переименована") + ".";
            audit(c, updateId, userId, "category-name:" + id, old, name, reply);
            ledger.execute(c, "DELETE FROM fund_ui WHERE user_id=? AND mode='category_name'", userId);
            return reply;
        });
    }

    public String activate(long updateId, long userId, long id, boolean active) {
        return ledger.transaction(c -> {
            access.requireManager(c, userId);
            String replay = replay(c, updateId);
            if (replay != null) return replay;
            if (active) {
                try (PreparedStatement p = c.prepareStatement("SELECT COUNT(*) FROM fund_categories WHERE active=1 AND id!=?")) {
                    p.setLong(1, id);
                    try (ResultSet r = p.executeQuery()) {
                        if (r.next() && r.getInt(1) >= 20) throw new FundLedger.InputException("Можно использовать до 20 активных категорий.");
                    }
                }
            }
            String name;
            try (PreparedStatement p = c.prepareStatement("SELECT name FROM fund_categories WHERE id=?")) {
                p.setLong(1, id);
                try (ResultSet r = p.executeQuery()) { if (!r.next()) throw new FundLedger.InputException("Категория не найдена."); name = r.getString(1); }
            }
            ledger.execute(c, "UPDATE fund_categories SET active=? WHERE id=?", active, id);
            String reply = "Категория «" + name + "» " + (active ? "восстановлена" : "в архиве") + ".\nКатегории ранее записанных расходов сохранены.";
            audit(c, updateId, userId, "category-active:" + id, null, Boolean.toString(active), reply);
            return reply;
        });
    }

    private String activeCategory(Connection c, long id) throws SQLException {
        if (id == 0) return "Без категории";
        try (PreparedStatement p = c.prepareStatement("SELECT name FROM fund_categories WHERE id=? AND active=1")) {
            p.setLong(1, id);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) throw new FundLedger.InputException("Категория недоступна. Выбери действующую категорию.");
                return r.getString(1);
            }
        }
    }

    private String replay(Connection c, long updateId) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT reply FROM fund_category_audit WHERE update_id=?")) {
            p.setLong(1, updateId);
            try (ResultSet r = p.executeQuery()) { return r.next() ? r.getString(1) : null; }
        }
    }

    private void audit(Connection c, long updateId, long actor, String subject, String oldValue, String newValue, String reply) throws SQLException {
        ledger.execute(c, "INSERT INTO fund_category_audit VALUES (?,?,?,?,?,?,?)", updateId, actor, subject, oldValue, newValue, Instant.now().toString(), reply);
    }
}
