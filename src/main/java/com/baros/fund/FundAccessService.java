package com.baros.fund;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.time.Instant;
import java.util.*;

@Service
@Profile("dvoyka")
public class FundAccessService {
    public enum Role { OWNER, MANAGER, EMPLOYEE, PENDING, REJECTED, REVOKED }
    public record Member(long id, String name, Role role, int version, String requestedAt) {
        public boolean active() { return role == Role.OWNER || role == Role.MANAGER || role == Role.EMPLOYEE; }
        public boolean manages() { return role == Role.OWNER || role == Role.MANAGER; }
    }
    public record Notification(long id, long chatId, String text) {}
    private final FundLedger ledger;

    @Autowired
    public FundAccessService(FundLedger ledger, @Value("${fund.allowed-user-ids:}") String legacyUsers,
                             @Value("${fund.owner-user-ids:}") String owners,
                             @Value("${fund.allowed-chat-ids:}") String legacyChats) {
        this.ledger = ledger;
        ledger.transaction(c -> {
            Set<Long> ownerIds = ids(owners);
            Set<Long> legacyIds = new LinkedHashSet<>(ids(legacyUsers));
            if (!legacyChats.isBlank()) for (String part : legacyChats.split(",")) {
                long id = Long.parseLong(part.trim());
                if (id > 0) legacyIds.add(id);
            }
            List<Long> removedOwners = new ArrayList<>();
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT user_id FROM fund_users WHERE role='OWNER'")) {
                while (r.next()) if (!ownerIds.contains(r.getLong(1))) removedOwners.add(r.getLong(1));
            }
            for (long id : removedOwners) {
                ledger.execute(c, "UPDATE fund_users SET role='REVOKED',version=version+1 WHERE user_id=?", id);
                for (String table : List.of("fund_drafts", "fund_history_views", "fund_ui")) ledger.execute(c, "DELETE FROM " + table + " WHERE user_id=?", id);
                ledger.execute(c, "DELETE FROM fund_notifications WHERE chat_id=?", id);
            }
            for (long id : legacyIds) ledger.execute(c,
                    "INSERT OR IGNORE INTO fund_users(user_id,name,role) VALUES (?,?,'MANAGER')", id, "Telegram " + id);
            for (long id : ownerIds) ledger.execute(c, """
                    INSERT INTO fund_users(user_id,name,role) VALUES (?,?,'OWNER') ON CONFLICT(user_id)
                    DO UPDATE SET role='OWNER',version=version+1 WHERE role!='OWNER'
                    """, id, "Telegram " + id);
            return null;
        });
    }

    FundAccessService(FundLedger ledger, String legacyUsers, String owners) {
        this(ledger, legacyUsers, owners, "");
    }

    private static Set<Long> ids(String value) {
        Set<Long> result = new LinkedHashSet<>();
        if (!value.isBlank()) for (String part : value.split(",")) {
            long id = Long.parseLong(part.trim());
            if (id <= 0) throw new IllegalArgumentException("User IDs must be positive");
            result.add(id);
        }
        return result;
    }

    public Member member(long id) { return ledger.transaction(c -> member(c, id)); }

    Member member(Connection c, long id) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT * FROM fund_users WHERE user_id=?")) {
            p.setLong(1, id);
            try (ResultSet r = p.executeQuery()) {
                return r.next() ? readMember(r) : null;
            }
        }
    }

    private Member readMember(ResultSet r) throws SQLException {
        return new Member(r.getLong("user_id"), r.getString("name"), Role.valueOf(r.getString("role")),
                r.getInt("version"), r.getString("requested_at"));
    }

    public void observe(FundLedger.Actor actor) {
        ledger.transaction(c -> { ledger.execute(c, "UPDATE fund_users SET name=? WHERE user_id=?", actor.name(), actor.id()); return null; });
    }

    public List<Member> members(int page, boolean pending) {
        return ledger.transaction(c -> {
            List<Member> result = new ArrayList<>();
            try (PreparedStatement p = c.prepareStatement("SELECT * FROM fund_users "
                    + (pending ? "WHERE role='PENDING' " : "") + "ORDER BY user_id LIMIT 8 OFFSET ?")) {
                p.setLong(1, (long) Math.max(0, page) * 7);
                try (ResultSet r = p.executeQuery()) { while (r.next()) result.add(readMember(r)); }
            }
            return result;
        });
    }

    public String request(long updateId, FundLedger.Actor actor) {
        return ledger.transaction(c -> {
            var current = member(c, actor.id());
            if (current != null && current.active()) return "У тебя уже есть доступ к боту.";
            String replay = replay(c, updateId);
            if (replay != null) return replay;
            if (current != null && current.role() == Role.PENDING) return "Заявка уже отправлена. Дождись решения владельца.";
            List<Long> owners = new ArrayList<>();
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT user_id FROM fund_users WHERE role='OWNER'")) {
                while (r.next()) owners.add(r.getLong(1));
            }
            if (owners.isEmpty()) throw new FundLedger.InputException("Владелец бота ещё не настроен. Передай администратору свой ID: " + actor.id());
            if (current != null && current.requestedAt() != null
                    && Instant.parse(current.requestedAt()).plusSeconds(3600).isAfter(Instant.now())) {
                throw new FundLedger.InputException("Повторную заявку можно отправить через час после предыдущей.");
            }
            ledger.execute(c, """
                    INSERT INTO fund_users(user_id,name,role,requested_at) VALUES (?,?,'PENDING',?)
                    ON CONFLICT(user_id) DO UPDATE SET name=excluded.name,role='PENDING',
                      requested_at=excluded.requested_at,version=version+1
                    """, actor.id(), actor.name(), Instant.now().toString());
            String reply = "Заявка на доступ отправлена владельцу. После одобрения нажми /start.";
            audit(c, updateId, actor.id(), actor.id(), current == null ? null : current.role(), Role.PENDING, reply);
            for (long owner : owners) notify(c, "request:" + updateId + ":" + owner, owner,
                    "Новая заявка на доступ: " + actor.name() + " [" + actor.id() + "].\nОткрой «Сотрудники» → «Заявки» или /staff.");
            return reply;
        });
    }

    void requireManager(Connection c, long id) throws SQLException {
        var m = member(c, id);
        if (m == null || !m.active() || !m.manages()) throw new FundLedger.InputException("Это действие доступно владельцу и управляющему.");
    }

    public void requireOwner(long id) {
        ledger.transaction(c -> { requireOwner(c, id); return null; });
    }

    private void requireOwner(Connection c, long id) throws SQLException {
        var m = member(c, id);
        if (m == null || m.role() != Role.OWNER) throw new FundLedger.InputException("Управление доступом доступно только владельцу.");
    }

    public String change(long updateId, long actorId, long targetId, int version, Role role) {
        return ledger.transaction(c -> {
            requireOwner(c, actorId);
            String replay = replay(c, updateId);
            if (replay != null) return replay;
            if (!Set.of(Role.MANAGER, Role.EMPLOYEE, Role.REJECTED, Role.REVOKED).contains(role))
                throw new FundLedger.InputException("Эту роль нельзя назначить через бота.");
            var target = member(c, targetId);
            if (target == null) throw new FundLedger.InputException("Пользователь не найден. Пусть сначала подаст заявку.");
            if (target.role() == Role.OWNER) throw new FundLedger.InputException("Доступ владельца задаётся в настройках сервера и защищён от отключения в боте.");
            if (target.version() != version) throw new FundLedger.InputException("Данные пользователя изменились. Открой список сотрудников заново.");
            if (role == Role.REJECTED && target.role() != Role.PENDING)
                throw new FundLedger.InputException("Эта заявка уже рассмотрена.");
            ledger.execute(c, "UPDATE fund_users SET role=?,version=version+1 WHERE user_id=?", role.name(), targetId);
            // Remove stale input and history after *any* role change, including demotion.
            for (String table : List.of("fund_drafts", "fund_history_views", "fund_ui"))
                ledger.execute(c, "DELETE FROM " + table + " WHERE user_id=?", targetId);
            String reply = target.name() + ": " + roleName(role) + ".";
            audit(c, updateId, actorId, targetId, target.role(), role, reply);
            notify(c, "decision:" + updateId, targetId, "Доступ к «Двойке»: " + roleName(role) + ".\nНажми /start, чтобы обновить меню.");
            return reply;
        });
    }

    private String replay(Connection c, long updateId) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT reply FROM fund_access_audit WHERE update_id=?")) {
            p.setLong(1, updateId);
            try (ResultSet r = p.executeQuery()) { return r.next() ? r.getString(1) : null; }
        }
    }

    private void audit(Connection c, long updateId, long actor, long target, Role oldRole, Role role, String reply) throws SQLException {
        ledger.execute(c, "INSERT INTO fund_access_audit VALUES (?,?,?,?,?,?,?)", updateId, actor, target,
                oldRole == null ? null : oldRole.name(), role.name(), Instant.now().toString(), reply);
    }

    private void notify(Connection c, String source, long chatId, String text) throws SQLException {
        ledger.execute(c, "INSERT OR IGNORE INTO fund_notifications(source,chat_id,text) VALUES (?,?,?)", source, chatId, text);
    }

    public List<Notification> notifications() {
        return ledger.transaction(c -> {
            List<Notification> result = new ArrayList<>();
            try (PreparedStatement p = c.prepareStatement("SELECT id,chat_id,text FROM fund_notifications WHERE next_attempt<=? ORDER BY id LIMIT 2")) {
                p.setLong(1, System.currentTimeMillis());
                try (ResultSet r = p.executeQuery()) { while (r.next()) result.add(new Notification(r.getLong(1), r.getLong(2), r.getString(3))); }
            }
            return result;
        });
    }

    public void notificationResult(long id, boolean success) {
        ledger.transaction(c -> {
            if (success) ledger.execute(c, "DELETE FROM fund_notifications WHERE id=?", id);
            else ledger.execute(c, "UPDATE fund_notifications SET next_attempt=? WHERE id=?", System.currentTimeMillis() + 300_000, id);
            return null;
        });
    }

    public static String roleName(Role role) {
        return switch (role) {
            case OWNER -> "Владелец"; case MANAGER -> "Управляющий"; case EMPLOYEE -> "Сотрудник";
            case PENDING -> "Ожидает одобрения"; case REJECTED -> "Заявка отклонена"; case REVOKED -> "Доступ отключён";
        };
    }
}
