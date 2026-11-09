package com.baros.fund;

import com.baros.telegram.TelegramClient.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

@Service
@Profile("dvoyka")
public class FundManagementUi {
    public static final String STAFF = "👥 Сотрудники";
    public static final String CATEGORIES = "🏷 Категории";
    public static final String UNCATEGORIZED = "🗂 Без категории";
    public static final String REQUEST = "🔑 Запросить доступ";
    public record Panel(String text, InlineKeyboardMarkup keyboard) {}
    private final FundLedger ledger;
    private final FundAccessService access;
    private final FundCategoryService categories;

    public FundManagementUi(FundLedger ledger, FundAccessService access, FundCategoryService categories) {
        this.ledger = ledger; this.access = access; this.categories = categories;
    }

    private void manager(long userId) {
        ledger.transaction(c -> { access.requireManager(c, userId); return null; });
    }

    public void clearInput(long chatId, long userId) {
        ledger.transaction(c -> { ledger.execute(c, "DELETE FROM fund_ui WHERE chat_id=? AND user_id=?", chatId, userId); return null; });
    }

    private void input(long chatId, long userId, long categoryId) {
        ledger.transaction(c -> {
            ledger.execute(c, """
                    INSERT INTO fund_ui VALUES (?,?,'category_name',?) ON CONFLICT(chat_id,user_id)
                    DO UPDATE SET mode=excluded.mode,value=excluded.value
                    """, chatId, userId, categoryId);
            ledger.execute(c, "DELETE FROM fund_drafts WHERE chat_id=? AND user_id=?", chatId, userId);
            ledger.execute(c, "DELETE FROM fund_history_views WHERE chat_id=? AND user_id=?", chatId, userId);
            return null;
        });
    }

    /** Explicit navigation cancels category-name input; money keeps its existing syntax. */
    public String text(long updateId, long chatId, long userId, String text) {
        String command = text.split("\\s+", 2)[0].split("@", 2)[0].toLowerCase(Locale.ROOT);
        if (text.equals(STAFF) || command.equals("/staff")) {
            access.requireOwner(userId); clearInput(chatId, userId);
            return "👥 Сотрудники · заявки, роли и отключение доступа кнопками ниже.";
        }
        if (text.equals(CATEGORIES) || command.equals("/categories")) {
            manager(userId); clearInput(chatId, userId);
            return "🏷 Категории · настрой список и категории для шаблонов. Старые расходы доступны в «Без категории».";
        }
        if (text.equals(UNCATEGORIZED)) { clearInput(chatId, userId); return "🗂 Расходы без категории · выбери запись ниже."; }
        if (command.equals("/category")) {
            String[] parts = text.split("\\s+");
            if (parts.length != 2 || !parts[1].matches("[1-9]\\d{0,17}")) throw new FundLedger.InputException("Отправь /category НОМЕР, например /category 28.");
            categories.requireExpenseAccess(userId, categories.expense(Long.parseLong(parts[1])));
            clearInput(chatId, userId);
            return "Выбери категорию расхода кнопкой ниже.";
        }
        // A reply may fail after a successful name save, so replay by Telegram update ID.
        String replay = ledger.transaction(c -> {
            try (PreparedStatement p = c.prepareStatement("SELECT reply FROM fund_category_audit WHERE update_id=? AND actor_id=? AND subject LIKE 'category-name:%'")) {
                p.setLong(1, updateId); p.setLong(2, userId);
                try (ResultSet r = p.executeQuery()) { return r.next() ? r.getString(1) : null; }
            }
        });
        if (replay != null) { manager(userId); return replay; }
        if (text.startsWith("/") || text.matches("^[+−-].*") || Set.of("💰 Остаток", "📒 История", "➕ Поступление", "➖ Расход", "ℹ️ Помощь", "🔄 Синхронизировать",
                "❌ Отмена", FundHistoryService.TODAY, FundHistoryService.WEEK, FundHistoryService.MONTH,
                FundHistoryService.NEXT, FundHistoryService.PREVIOUS, FundHistoryService.MENU).contains(text)) {
            clearInput(chatId, userId); return null;
        }
        Long categoryId = ledger.transaction(c -> {
            try (PreparedStatement p = c.prepareStatement("SELECT value FROM fund_ui WHERE chat_id=? AND user_id=? AND mode='category_name'")) {
                p.setLong(1, chatId); p.setLong(2, userId);
                try (ResultSet r = p.executeQuery()) { return r.next() ? r.getLong(1) : null; }
            }
        });
        return categoryId == null ? null : categories.saveName(updateId, userId, categoryId, text);
    }

    public Panel afterText(long updateId, long userId, String text) {
        var entry = ledger.manualEntry(updateId);
        if (entry != null && entry.cents() < 0) {
            var e = categories.expense(entry.id());
            categories.requireExpenseAccess(userId, e);
            return panel("Расход #" + e.id() + " · категория: " + categories.categoryName(e.categoryId()),
                    row(e.categoryId() == 0 ? "Выбрать категорию" : "Изменить категорию", "expense:" + e.id()));
        }
        String command = text.split("\\s+", 2)[0].split("@", 2)[0].toLowerCase(Locale.ROOT);
        if (text.equals(STAFF) || command.equals("/staff")) return staff(userId, 0, false);
        if (text.equals(CATEGORIES) || command.equals("/categories")) return categoryList(userId, 0);
        if (text.equals(UNCATEGORIZED)) return uncategorized(userId, 0);
        if (command.equals("/category")) {
            String[] parts = text.split("\\s+");
            if (parts.length == 2 && parts[1].matches("[1-9]\\d{0,17}")) return expense(userId, Long.parseLong(parts[1]));
        }
        return null;
    }

    public Panel callback(long updateId, long chatId, long userId, String data) {
        if (data == null || data.length() > 64) throw new FundLedger.InputException("Кнопка недействительна.");
        String[] p = data.split(":");
        clearInput(chatId, userId);
        try {
            return switch (p[0]) {
                case "staff" -> staff(userId, page(p[1]), false);
                case "pending" -> staff(userId, page(p[1]), true);
                case "user" -> user(userId, Long.parseLong(p[1]));
                case "role" -> {
                    var role = switch (p[4]) {
                        case "M" -> FundAccessService.Role.MANAGER; case "E" -> FundAccessService.Role.EMPLOYEE;
                        case "J" -> FundAccessService.Role.REJECTED; case "R" -> FundAccessService.Role.REVOKED;
                        default -> throw new FundLedger.InputException("Неизвестная роль.");
                    };
                    if (!p[1].equals("set")) throw new FundLedger.InputException("Кнопка недействительна.");
                    yield panel(access.change(updateId, userId, Long.parseLong(p[2]), Integer.parseInt(p[3]), role),
                            row("👥 Сотрудники", "staff:0"), row("Заявки", "pending:0"));
                }
                case "cats" -> categoryList(userId, page(p[1]));
                case "cat" -> category(userId, Long.parseLong(p[1]));
                case "newcat", "rename" -> {
                    manager(userId);
                    long id = p[0].equals("newcat") ? 0 : Long.parseLong(p[1]);
                    input(chatId, userId, id);
                    yield panel("Отправь " + (id == 0 ? "название новой категории" : "новое название категории")
                            + " одним сообщением, до 48 символов. Для отмены — /cancel.", row("Отмена", "cancel:0"));
                }
                case "active" -> {
                    if (!Set.of("0", "1").contains(p[2])) throw new FundLedger.InputException("Кнопка недействительна.");
                    yield panel(categories.activate(updateId, userId, Long.parseLong(p[1]), p[2].equals("1")), row("🏷 Категории", "cats:0"));
                }
                case "uncat" -> uncategorized(userId, Long.parseLong(p[1]));
                case "expense" -> expense(userId, Long.parseLong(p[1]));
                case "assign" -> {
                    String answer = categories.assign(updateId, userId, Long.parseLong(p[1]), Long.parseLong(p[2]), Integer.parseInt(p[3]));
                    yield panel(answer, row("Изменить категорию", "expense:" + p[1]), row("🗂 Без категории", "uncat:0"));
                }
                case "templates" -> templates(userId);
                case "template" -> template(userId, Integer.parseInt(p[1]));
                case "rule" -> panel(categories.setTemplate(updateId, userId, Integer.parseInt(p[1]), Long.parseLong(p[2]), Integer.parseInt(p[3])),
                        row("Шаблоны", "templates:0"), row("🏷 Категории", "cats:0"));
                case "cancel" -> { clearInput(chatId, userId); yield panel("Ввод отменён.", row("🏷 Категории", "cats:0")); }
                default -> throw new FundLedger.InputException("Кнопка устарела. Открой меню заново.");
            };
        } catch (NumberFormatException | IndexOutOfBoundsException e) { throw new FundLedger.InputException("Кнопка недействительна. Открой меню заново."); }
    }

    private int page(String value) { return Math.min(1_000_000, Math.max(0, Integer.parseInt(value))); }

    private Panel staff(long userId, int page, boolean pending) {
        access.requireOwner(userId);
        var members = access.members(page, pending);
        var rows = new ArrayList<List<InlineButton>>();
        for (var m : members.stream().limit(7).toList()) rows.add(row(m.name() + " · " + FundAccessService.roleName(m.role()), "user:" + m.id()));
        if (page > 0) rows.add(row("◀️ Назад", (pending ? "pending:" : "staff:") + (page - 1)));
        if (members.size() > 7) rows.add(row("Вперёд ▶️", (pending ? "pending:" : "staff:") + (page + 1)));
        rows.add(row(pending ? "Все сотрудники" : "Заявки", pending ? "staff:0" : "pending:0"));
        return new Panel((pending ? "Заявки на доступ" : "Сотрудники и доступ") + " · страница " + (page + 1)
                + (members.isEmpty() ? "\nСписок пуст." : "\nВыбери человека."), new InlineKeyboardMarkup(rows));
    }

    private Panel user(long actor, long target) {
        access.requireOwner(actor);
        var m = access.member(target);
        if (m == null) throw new FundLedger.InputException("Пользователь не найден.");
        var rows = new ArrayList<List<InlineButton>>();
        if (m.role() != FundAccessService.Role.OWNER) {
            String prefix = "role:set:" + target + ":" + m.version() + ":";
            rows.add(row("Разрешить · Сотрудник", prefix + "E"));
            rows.add(row("Разрешить · Управляющий", prefix + "M"));
            rows.add(row(m.role() == FundAccessService.Role.PENDING ? "Отклонить заявку" : "Отключить доступ", prefix + (m.role() == FundAccessService.Role.PENDING ? "J" : "R")));
        }
        rows.add(row("👥 Сотрудники", "staff:0"));
        return new Panel(m.name() + "\nTelegram ID: " + m.id() + "\nРоль: " + FundAccessService.roleName(m.role()), new InlineKeyboardMarkup(rows));
    }

    private Panel categoryList(long userId, int page) {
        manager(userId);
        var all = categories.categories(false);
        var rows = new ArrayList<List<InlineButton>>();
        all.stream().skip((long) page * 7).limit(7).forEach(c -> rows.add(row(c.name() + (c.active() ? "" : " · архив"), "cat:" + c.id())));
        if (page > 0) rows.add(row("◀️ Назад", "cats:" + (page - 1)));
        if ((long) (page + 1) * 7 < all.size()) rows.add(row("Вперёд ▶️", "cats:" + (page + 1)));
        rows.add(row("➕ Добавить категорию", "newcat:0"));
        rows.add(row("Категории шаблонов", "templates:0"));
        rows.add(row("🗂 Расходы без категории", "uncat:0"));
        return new Panel("🏷 Категории · страница " + (page + 1) + "\nНажми на категорию для настройки.", new InlineKeyboardMarkup(rows));
    }

    private Panel category(long userId, long id) {
        manager(userId);
        var c = categories.categories(false).stream().filter(x -> x.id() == id).findFirst()
                .orElseThrow(() -> new FundLedger.InputException("Категория не найдена."));
        return panel(c.name() + (c.active() ? " · активна" : " · в архиве"), row("Переименовать", "rename:" + id),
                row(c.active() ? "Убрать в архив" : "Восстановить", "active:" + id + ":" + (c.active() ? "0" : "1")), row("🏷 Категории", "cats:0"));
    }

    private Panel expense(long userId, long id) {
        var e = categories.expense(id);
        categories.requireExpenseAccess(userId, e);
        var rows = new ArrayList<List<InlineButton>>();
        for (var c : categories.categories(true)) rows.add(row(c.name(), "assign:" + id + ":" + c.id() + ":" + e.version()));
        rows.add(row("Без категории", "assign:" + id + ":0:" + e.version()));
        return new Panel("Расход #" + id + " · " + FundLedger.money(-e.cents()) + "\n" + e.note()
                + "\nКатегория: " + categories.categoryName(e.categoryId()) + "\nМожно выбрать другую кнопкой.", new InlineKeyboardMarkup(rows));
    }

    private Panel uncategorized(long userId, long before) {
        var expenses = categories.uncategorized(userId, before);
        var rows = new ArrayList<List<InlineButton>>();
        expenses.stream().limit(7).forEach(e -> rows.add(row("#" + e.id() + " · " + FundLedger.money(-e.cents()) + " · " + FundLedger.clean(e.note(), 40), "expense:" + e.id())));
        if (expenses.size() > 7) rows.add(row("Ещё расходы ▶️", "uncat:" + expenses.get(6).id()));
        rows.add(row("Обновить список", "uncat:0"));
        return new Panel("🗂 Без категории" + (expenses.isEmpty() ? "\nТаких расходов нет." : "\nВыбери расход. Сотруднику доступны только свои записи."), new InlineKeyboardMarkup(rows));
    }

    private Panel templates(long userId) {
        manager(userId);
        var rows = new ArrayList<List<InlineButton>>();
        for (int i = 0; i < FundCategoryService.TEMPLATES.size(); i++) rows.add(row(FundCategoryService.TEMPLATES.get(i), "template:" + i));
        rows.add(row("🏷 Категории", "cats:0"));
        return new Panel("Категории шаблонов\nВыбери шаблон. Пока правило не настроено, расход сохраняется без категории.", new InlineKeyboardMarkup(rows));
    }

    private Panel template(long userId, int index) {
        manager(userId);
        String name = FundCategoryService.TEMPLATES.get(index);
        var t = categories.templates().stream().filter(x -> x.name().equals(name)).findFirst().orElseThrow();
        var rows = new ArrayList<List<InlineButton>>();
        for (var c : categories.categories(true)) rows.add(row(c.name(), "rule:" + index + ":" + c.id() + ":" + t.version()));
        rows.add(row("Без категории", "rule:" + index + ":0:" + t.version()));
        boolean archived = t.categoryId() != 0 && categories.categories(true).stream().noneMatch(c -> c.id() == t.categoryId());
        return new Panel("Шаблон «" + name + "»\nКатегория: " + categories.categoryName(t.categoryId())
                + (archived ? " (в архиве; новые расходы будут без категории)" : "")
                + "\nВыбери категорию для новых расходов.", new InlineKeyboardMarkup(rows));
    }

    private static List<InlineButton> row(String text, String data) { return List.of(new InlineButton(text, data)); }
    @SafeVarargs private static Panel panel(String text, List<InlineButton>... rows) { return new Panel(text, new InlineKeyboardMarkup(List.of(rows))); }
}
