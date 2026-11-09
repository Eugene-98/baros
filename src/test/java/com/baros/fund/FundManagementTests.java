package com.baros.fund;

import com.baros.telegram.TelegramUpdateResponse.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FundManagementTests {
    @TempDir Path temp;
    FundLedger ledger;
    FundAccessService access;
    FundCategoryService categories;
    FundManagementUi ui;
    FundCommandHandler commands;
    FundSyncService sync;
    long next = 100;

    @BeforeEach void setup() throws Exception {
        ledger = new FundLedger(temp.resolve("fund.sqlite").toString(), 8);
        access = new FundAccessService(ledger, "123,456", "123");
        categories = new FundCategoryService(ledger, access);
        ui = new FundManagementUi(ledger, access, categories);
        sync = mock(FundSyncService.class);
        when(sync.currentBusinessDate()).thenReturn(LocalDate.now(ZoneId.of("Europe/Minsk")));
        commands = handler(ledger, access, ui);
        ledger.opening(1, 100_000, LocalDate.now().minusDays(1), new FundLedger.Actor(123, "Владелец"));
    }

    FundCommandHandler handler(FundLedger l, FundAccessService a, FundManagementUi u) {
        return new FundCommandHandler(l, sync, "123,456", "-1001", "Europe/Minsk", new FundHistoryService(l, "Europe/Minsk", 8), a, u);
    }

    Update message(long update, long user, long chat, String type, String text) {
        return new Update(update, new Message(new Chat(chat, type), text, new User(user, "User " + user, null, "user" + user, false)));
    }
    String say(long user, String text) { return commands.handle(message(next++, user, user, "private", text)); }
    Update callback(long update, long user, String data) {
        return new Update(update, null, new CallbackQuery("callback-" + update, new User(user, "User", null, null, false),
                new Message(new Chat(user, "private"), null, null), data));
    }
    void grant(long user, FundAccessService.Role role) {
        say(user, "/request");
        access.change(next++, 123, user, access.member(user).version(), role);
    }
    long categoryId(String name) { return categories.categories(true).stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow().id(); }
    long expense(long user, String note) { say(user, "-20 " + note); return ledger.history().getFirst().id(); }

    @Test void requestApprovalAndNotificationsAreDurableAndDeduplicated() throws Exception {
        Update request = message(next++, 789, 789, "private", FundManagementUi.REQUEST);
        assertThat(commands.handle(request)).contains("отправлена");
        assertThat(commands.handle(request)).contains("отправлена");
        assertThat(access.member(789).role()).isEqualTo(FundAccessService.Role.PENDING);
        assertThat(access.notifications()).hasSize(1);
        assertThat(access.notifications().getFirst().text()).contains("789", "заявка");
        assertThat(say(789, "/balance")).doesNotContain("1000,00");
        int version = access.member(789).version();
        Update approval = callback(next++, 123, "role:set:789:" + version + ":E");
        assertThat(commands.callback(approval).text()).contains("Сотрудник");
        assertThat(commands.callback(approval).text()).contains("Сотрудник");
        assertThat(access.member(789).version()).isEqualTo(version + 1);
        assertThat(say(789, "/balance")).contains("1000,00");
        assertThat(access.notifications()).hasSize(2);
        var restarted = new FundAccessService(new FundLedger(temp.resolve("fund.sqlite").toString(), 8), "123,456", "123");
        assertThat(restarted.member(789).role()).isEqualTo(FundAccessService.Role.EMPLOYEE);
        assertThat(restarted.notifications()).hasSize(2);
    }

    @Test void rolesAreCheckedOnEveryActionIncludingForgedAndOldButtons() {
        grant(789, FundAccessService.Role.EMPLOYEE);
        assertThat(say(789, "/staff")).contains("только владельцу");
        assertThat(say(456, "/staff")).contains("только владельцу");
        assertThat(say(789, "/categories")).contains("владельцу и управляющему");
        assertThat(say(789, "/opening 1 01.01.2026")).contains("только владелец");
        assertThat(say(789, "/sync")).contains("доступна владельцу и управляющему");
        assertThat(commands.callback(callback(next++, 456, "role:set:789:2:M")).text()).contains("только владельцу");
        assertThat(commands.callback(callback(next++, 789, "newcat:0")).text()).contains("владельцу и управляющему");
        int version = access.member(789).version();
        String staleGrant = "role:set:789:" + version + ":M";
        access.change(next++, 123, 789, version, FundAccessService.Role.REVOKED);
        assertThat(commands.callback(callback(next++, 123, staleGrant)).text()).contains("изменились");
        assertThat(access.member(789).role()).isEqualTo(FundAccessService.Role.REVOKED);
        verify(sync, never()).sync(any());
    }

    @Test void revocationCannotBeBypassedThroughLegacyAllowlistOrGroup() {
        long id = expense(456, "Старая запись");
        commands.handle(message(next++, 456, -1001, "supergroup", "➖ Расход"));
        access.change(next++, 123, 456, access.member(456).version(), FundAccessService.Role.REVOKED);
        assertThat(ledger.draft(-1001, 456)).isNull();
        new FundAccessService(ledger, "123,456", "123");
        assertThat(access.member(456).role()).isEqualTo(FundAccessService.Role.REVOKED);
        assertThat(say(456, "-20 Запрещённый расход")).contains("не предоставлен");
        assertThat(commands.handle(message(next++, 456, -1001, "supergroup", "/balance"))).doesNotContain("980,00");
        assertThat(commands.callback(callback(next++, 456, "expense:" + id)).text()).contains("отключён");
        assertThat(ledger.snapshot().balance()).isEqualTo(98_000);
        assertThat(ledger.history()).hasSize(2);
        assertThatThrownBy(() -> access.change(next++, 123, 123, access.member(123).version(), FundAccessService.Role.REVOKED))
                .hasMessageContaining("защищён");
    }

    @Test void groupPermissionRequiresIndividualMembershipAndDoesNotGrantOpening() {
        assertThat(commands.handle(message(next++, 777, -1001, "supergroup", "-20 Нельзя"))).contains("не предоставлен");
        assertThat(commands.handle(message(next++, 456, -1002, "supergroup", "/balance"))).contains("не настроен");
        assertThat(commands.handle(message(next++, 456, -1001, "supergroup", "-20 Можно"))).contains("980,00");
        assertThat(commands.handle(message(next++, 456, -1001, "supergroup", "/opening 1 01.01.2026"))).contains("только владелец");
        assertThat(commands.handle(message(next++, 123, -1001, "supergroup", "/staff"))).contains("личном диалоге");
    }

    @Test void employeeHistoryIsFilteredInSqlAndDemotionRemovesOldFullHistory() {
        grant(789, FundAccessService.Role.EMPLOYEE);
        for (int i = 0; i < 10; i++) expense(789, "Своя " + i);
        expense(123, "Закрытая запись владельца");
        String own = say(789, "/history month");
        assertThat(own).contains("Только твои", "Операций: 10", "Страница 1/2").doesNotContain("Закрытая", "Начальный остаток");
        assertThat(say(789, "Вперёд ▶️")).contains("Страница 2/2").doesNotContain("Закрытая");
        assertThat(say(456, "/history month")).contains("Закрытая запись владельца");
        access.change(next++, 123, 456, access.member(456).version(), FundAccessService.Role.EMPLOYEE);
        assertThat(ledger.historyView(456, 456)).isNull();
        assertThat(say(456, "/history month")).contains("Операций: 0").doesNotContain("Закрытая");
    }

    @Test void categoriesAssignToOldAndNewExpensesWithoutTouchingMoneyNotesOrAuthors() {
        grant(789, FundAccessService.Role.EMPLOYEE);
        long own = expense(789, "Лимоны");
        long other = expense(123, "Секрет");
        long balance = ledger.snapshot().balance();
        long cat = categoryId("Продукты и ингредиенты");
        assertThat(categories.uncategorized(789, 0)).extracting(FundCategoryService.Expense::id).containsExactly(own);
        assertThatThrownBy(() -> categories.assign(next++, 789, other, cat, 0)).hasMessageContaining("недоступен");
        long update = next++;
        assertThat(categories.assign(update, 789, own, cat, 0)).contains("Продукты");
        assertThat(categories.assign(update, 789, own, cat, 0)).contains("Продукты");
        assertThat(categories.expense(own).version()).isEqualTo(1);
        assertThat(categories.uncategorized(789, 0)).isEmpty();
        assertThat(ledger.snapshot().balance()).isEqualTo(balance);
        assertThat(ledger.history()).anySatisfy(e -> {
            assertThat(e.id()).isEqualTo(own); assertThat(e.note()).isEqualTo("Лимоны"); assertThat(e.actor().id()).isEqualTo(789);
        });
        assertThat(say(789, "/history month")).contains("Лимоны · Продукты и ингредиенты");
    }

    @Test void staleCategoryButtonsCannotOverwriteNewerAssignmentsAndIncomeCannotBeCategorized() {
        long id = expense(456, "Лимоны");
        long cat = categoryId("Продукты и ингредиенты");
        categories.assign(next++, 456, id, cat, 0);
        assertThat(commands.callback(callback(next++, 456, "assign:" + id + ":0:0")).text()).contains("уже изменена");
        assertThat(ledger.expenseCategory(id)).isEqualTo("Продукты и ингредиенты");
        say(456, "+50 Возврат");
        assertThat(commands.callback(callback(next++, 456, "expense:" + ledger.history().getFirst().id())).text()).contains("не найден");
        assertThat(categories.expense(id).version()).isEqualTo(1);
    }

    @Test void templateDefaultsAreExplicitAndAppliedAtomicallyOnlyToNewMatchingExpenses() throws Exception {
        long old = expense(456, "Рынок");
        assertThat(ledger.expenseCategory(old)).isEqualTo("Без категории");
        int index = FundCategoryService.TEMPLATES.indexOf("Рынок");
        var template = categories.templates().stream().filter(t -> t.name().equals("Рынок")).findFirst().orElseThrow();
        long cat = categoryId("Продукты и ингредиенты");
        categories.setTemplate(next++, 456, index, cat, template.version());
        say(456, "➖ Расход"); say(456, "Рынок");
        Update save = message(next++, 456, 456, "private", "20,00");
        assertThat(commands.handle(save)).contains("Расход записан");
        long id = ledger.manualEntry(save.updateId()).id();
        assertThat(ledger.expenseCategory(id)).isEqualTo("Продукты и ингредиенты");
        assertThat(ledger.expenseCategory(old)).isEqualTo("Без категории");
        assertThat(commands.afterText(save).text()).contains("Продукты и ингредиенты");
        assertThat(commands.handle(save)).contains("Расход записан");
        assertThat(ledger.history()).hasSize(3);
        var restarted = new FundLedger(temp.resolve("fund.sqlite").toString(), 8);
        assertThat(restarted.expenseCategory(id)).isEqualTo("Продукты и ингредиенты");
        assertThat(categories.templates().stream().filter(t -> t.name().equals("Рынок")).findFirst().orElseThrow().categoryId()).isEqualTo(cat);
    }

    @Test void archivedCategoryRemainsOnPastEntriesButIsNotUsedByTemplatesOrNewAssignments() {
        long cat = categoryId("Продукты и ингредиенты");
        categories.setTemplate(next++, 456, 1, cat, 1);
        long old = expense(456, "Рынок");
        categories.activate(next++, 456, cat, false);
        assertThat(ledger.expenseCategory(old)).isEqualTo("Продукты и ингредиенты");
        long fresh = expense(456, "Рынок");
        assertThat(ledger.expenseCategory(fresh)).isEqualTo("Без категории");
        assertThatThrownBy(() -> categories.assign(next++, 456, fresh, cat, 0)).hasMessageContaining("недоступна");
        categories.activate(next++, 456, cat, true);
        assertThat(categories.categories(true)).extracting(FundCategoryService.Category::id).contains(cat);
    }

    @Test void categoryCreationRenameAndCancellationSurviveRestartAndReplyRetry() throws Exception {
        assertThat(commands.callback(callback(next++, 456, "newcat:0")).text()).contains("название новой");
        var restarted = new FundLedger(temp.resolve("fund.sqlite").toString(), 8);
        commands = handler(restarted, access, new FundManagementUi(restarted, access, new FundCategoryService(restarted, access)));
        Update name = message(next++, 456, 456, "private", "Такси после смены");
        assertThat(commands.handle(name)).contains("добавлена");
        assertThat(commands.handle(name)).contains("добавлена");
        assertThat(categories.categories(true).stream().filter(c -> c.name().equals("Такси после смены"))).hasSize(1);
        long id = categoryId("Такси после смены");
        commands.callback(callback(next++, 456, "rename:" + id));
        assertThat(say(456, "Такси сотрудников")).contains("переименована");
        commands.callback(callback(next++, 456, "newcat:0"));
        say(456, "/cancel"); say(456, "Не категория");
        assertThat(categories.categories(true)).extracting(FundCategoryService.Category::name).doesNotContain("Не категория");
        assertThatThrownBy(() -> categories.saveName(next++, 456, 0, "такси СОТРУДНИКОВ")).hasMessageContaining("уже существует");
    }

    @Test void categoryChoicesAndNotificationsDoNotExposeDataAfterRevocationOrToBots() {
        long id = expense(456, "Лимоны");
        access.change(next++, 123, 456, access.member(456).version(), FundAccessService.Role.REVOKED);
        assertThat(commands.callback(callback(next++, 456, "expense:" + id)).text()).doesNotContain("Лимоны");
        var bot = new User(123L, "Bot", null, null, true);
        var forged = new Update(next++, null, new CallbackQuery("bad", bot, new Message(new Chat(123L, "private"), null, null), "staff:0"));
        assertThat(commands.callback(forged).text()).doesNotContain("Сотрудники и доступ");
    }

    @Test void legacySchemaMigrationPreservesFinancialDataAndDoesNotGuessCategories() throws Exception {
        String filename = temp.resolve("legacy.sqlite").toString();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + filename); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE fund_meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
            s.execute("INSERT INTO fund_meta VALUES ('start_date','2026-10-20')");
            s.execute("CREATE TABLE fund_entries(id INTEGER PRIMARY KEY AUTOINCREMENT,cents INTEGER NOT NULL,kind TEXT NOT NULL,note TEXT NOT NULL,actor_id INTEGER NOT NULL,actor_name TEXT NOT NULL,created_at TEXT NOT NULL,source_key TEXT UNIQUE NOT NULL)");
            s.execute("INSERT INTO fund_entries VALUES(1,100000,'OPENING','Старый остаток',123,'Женя','2026-10-20T08:00:00Z','tg:1')");
            s.execute("INSERT INTO fund_entries VALUES(2,-2500,'EXPENSE','Рынок',456,'Сотрудник','2026-10-21T08:00:00Z','tg:2')");
        }
        var migrated = new FundLedger(filename, 8);
        assertThat(migrated.snapshot().balance()).isEqualTo(97_500);
        assertThat(migrated.snapshot().startHour()).isEqualTo(3);
        assertThat(migrated.history()).hasSize(2);
        assertThat(migrated.expenseCategory(2)).isEqualTo("Без категории");
        assertThat(migrated.history().getFirst().actor().name()).isEqualTo("Сотрудник");
        new FundLedger(filename, 8);
        assertThat(migrated.snapshot().balance()).isEqualTo(97_500);
    }

    @Test void approvalReplyFailureAndRestartDoNotApplyRoleChangeTwice() throws Exception {
        say(789, "/request");
        int version = access.member(789).version();
        var update = callback(next++, 123, "role:set:789:" + version + ":E");
        var telegram = mock(com.baros.telegram.TelegramClient.class);
        when(telegram.getUpdates(0)).thenReturn(List.of(update));
        doThrow(new IllegalStateException("Telegram unavailable")).doNothing().when(telegram).sendInlineMessage(anyLong(), anyString(), any());
        assertThatThrownBy(() -> new FundPollingService(telegram, commands, ledger).pollOnce()).isInstanceOf(IllegalStateException.class);
        assertThat(ledger.offset()).isZero();
        assertThat(access.member(789).version()).isEqualTo(version + 1);
        var restarted = new FundLedger(temp.resolve("fund.sqlite").toString(), 8);
        var a = new FundAccessService(restarted, "123,456", "123");
        var u = new FundManagementUi(restarted, a, new FundCategoryService(restarted, a));
        new FundPollingService(telegram, handler(restarted, a, u), restarted).pollOnce();
        assertThat(a.member(789).version()).isEqualTo(version + 1);
        assertThat(restarted.offset()).isEqualTo(update.updateId() + 1);
        assertThat(restarted.snapshot().balance()).isEqualTo(100_000);
        verify(telegram).answerCallback(update.callbackQuery().id());
    }

    @Test void categoryPromptFailureDoesNotDuplicateExpenseOnRetry() throws Exception {
        var update = message(next++, 456, 456, "private", "-20,00 Лимоны");
        var telegram = mock(com.baros.telegram.TelegramClient.class);
        when(telegram.getUpdates(0)).thenReturn(List.of(update));
        doThrow(new IllegalStateException("Telegram unavailable")).doNothing().when(telegram).sendInlineMessage(anyLong(), anyString(), any());
        var poller = new FundPollingService(telegram, commands, ledger);
        assertThatThrownBy(poller::pollOnce).isInstanceOf(IllegalStateException.class);
        assertThat(ledger.snapshot().balance()).isEqualTo(98_000);
        assertThat(ledger.offset()).isZero();
        poller.pollOnce();
        assertThat(ledger.history()).hasSize(2);
        assertThat(ledger.snapshot().balance()).isEqualTo(98_000);
        assertThat(ledger.offset()).isEqualTo(update.updateId() + 1);
    }

    @Test void failedOwnerNotificationDoesNotBlockMoneyOrOffsetAndRemainsQueued() {
        say(789, "/request");
        var update = message(next++, 456, 456, "private", "+20 Возврат");
        var telegram = mock(com.baros.telegram.TelegramClient.class);
        when(telegram.getUpdates(0)).thenReturn(List.of(update));
        doThrow(new IllegalStateException("Blocked by recipient")).when(telegram).sendMessage(eq(123L), contains("Новая заявка"), isNull());
        new FundPollingService(telegram, commands, ledger).pollOnce();
        assertThat(ledger.snapshot().balance()).isEqualTo(102_000);
        assertThat(ledger.offset()).isEqualTo(update.updateId() + 1);
        assertThat(access.notifications()).isEmpty(); // Retry is delayed, not discarded.
        long queued = ledger.transaction(c -> {
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM fund_notifications")) { r.next(); return r.getLong(1); }
        });
        assertThat(queued).isEqualTo(1);
    }

    @Test void configuredOwnerRemovalAndPrivateChatMigrationCannotRestoreRevokedUsers() {
        var a = new FundAccessService(ledger, "123,456", "123,456", "777,-1001");
        assertThat(a.member(456).role()).isEqualTo(FundAccessService.Role.OWNER);
        assertThat(a.member(777).role()).isEqualTo(FundAccessService.Role.MANAGER);
        assertThat(a.member(-1001)).isNull();
        a = new FundAccessService(ledger, "123,456", "123", "777,-1001");
        assertThat(a.member(456).role()).isEqualTo(FundAccessService.Role.REVOKED);
        assertThat(a.member(123).role()).isEqualTo(FundAccessService.Role.OWNER);
    }

    @Test void employeeScopeNeverIncludesOldOpeningOrEsuplRecordsFromTheirManagerDays() {
        ledger.reconcile(Map.of(42L, new FundLedger.SaleAmount(5000, LocalDate.now().minusDays(1))),
                LocalDate.now().minusDays(1), LocalDate.now().minusDays(1), new FundLedger.Actor(456, "Управляющий"));
        expense(456, "Своя закупка");
        access.change(next++, 123, 456, access.member(456).version(), FundAccessService.Role.EMPLOYEE);
        assertThat(say(456, "/history month")).contains("Операций: 1", "Своя закупка").doesNotContain("Закрытый чек");
    }
}
