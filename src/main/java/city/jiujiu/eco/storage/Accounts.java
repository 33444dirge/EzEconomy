package city.jiujiu.eco.storage;

import java.math.BigDecimal;
import java.sql.*;
import java.util.UUID;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Single-currency wallet. Amounts are stored as exact units of 10^-8. */
public final class Accounts implements AutoCloseable {
    public static final class Rejected extends IllegalArgumentException {
        public final String messageKey;
        public Rejected(String key, String reason) { super(reason); this.messageKey = key; }
    }
    @FunctionalInterface public interface Connections { Connection open() throws SQLException; }
    private Connection db;
    private final Connections connections;
    private boolean closed, inTransaction;
    private final boolean mysql;
    private final Map<UUID, Long> display = new ConcurrentHashMap<>();
    private final Set<UUID> requested = new HashSet<>();
    public Accounts(Connection db, boolean mysql) throws SQLException {
        this(db,mysql,null);
    }
    public Accounts(Connection db, boolean mysql, Connections connections) throws SQLException {
        this.connections = connections;
        this.db = db;
        this.mysql = mysql;
        try (Statement s = db.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS jiujiueco_wallet (player VARCHAR(36) PRIMARY KEY, units BIGINT NOT NULL)");
            try (ResultSet rows = s.executeQuery("SELECT player, units FROM jiujiueco_wallet WHERE units <> 0")) {
                while (rows.next()) display.put(UUID.fromString(rows.getString(1)), rows.getLong(2));
            }
        }
    }
    public static long units(BigDecimal amount) {
        if (amount.signum() < 0) throw new Rejected("error.negative", "Negative amount");
        return amount.movePointRight(8).longValueExact();
    }
    public static BigDecimal amount(long units) { return BigDecimal.valueOf(units, 8).stripTrailingZeros(); }
    private Connection connection() throws SQLException {
        if(closed) throw new SQLException("Wallet closed");
        if(!inTransaction && db.isClosed() && connections != null) db=connections.open();
        return db;
    }
    public synchronized long balance(UUID id) throws SQLException {
        try (PreparedStatement s = connection().prepareStatement("SELECT units FROM jiujiueco_wallet WHERE player=?")) {
            s.setString(1, id.toString());
            try (ResultSet r = s.executeQuery()) { return r.next() ? r.getLong(1) : 0; }
        }
    }
    public synchronized boolean exists(UUID id) throws SQLException {
        try (PreparedStatement s = connection().prepareStatement("SELECT 1 FROM jiujiueco_wallet WHERE player=?")) {
            s.setString(1,id.toString());
            try (ResultSet r=s.executeQuery()) { return r.next(); }
        }
    }
    public synchronized boolean create(UUID id) throws SQLException {
        String sql = "INSERT INTO jiujiueco_wallet(player,units) VALUES(?,0)" +
            (mysql ? " ON DUPLICATE KEY UPDATE player=VALUES(player)" : " ON CONFLICT(player) DO NOTHING");
        try (PreparedStatement s=connection().prepareStatement(sql)) {
            s.setString(1,id.toString()); s.executeUpdate(); return true;
        }
    }
    public enum Change { GIVE, TAKE, SET }
    /** Memory-only snapshot for PAPI readers. Publish after the database commits. */
    public long displayBalance(UUID id) {
        if (mysql) synchronized (requested) { requested.add(id); }
        return display.getOrDefault(id, 0L);
    }
    public boolean sharedDatabase() { return mysql; }

    /** Refresh only displayed accounts, in indexed batches, for writes made by other servers. */
    public synchronized void refreshDisplays() throws SQLException {
        List<UUID> ids;
        synchronized (requested) {
            if (requested.isEmpty()) return;
            ids = new ArrayList<>(requested);
            requested.clear();
        }
        for (int offset = 0; offset < ids.size(); offset += 256) {
            List<UUID> batch = ids.subList(offset, Math.min(offset + 256, ids.size()));
            String slots = String.join(",", Collections.nCopies(batch.size(), "?"));
            try (PreparedStatement query = connection().prepareStatement("SELECT player,units FROM jiujiueco_wallet WHERE player IN (" + slots + ")")) {
                for (int i = 0; i < batch.size(); i++) query.setString(i + 1, batch.get(i).toString());
                Set<UUID> missing = new HashSet<>(batch);
                try (ResultSet rows = query.executeQuery()) {
                    while (rows.next()) {
                        UUID id = UUID.fromString(rows.getString(1));
                        publish(id, rows.getLong(2)); missing.remove(id);
                    }
                }
                for (UUID id : missing) display.remove(id);
            }
        }
    }
    private void publish(UUID id, long balance) {
        if (balance == 0) display.remove(id);
        else display.put(id, balance);
    }
    /** Returns the committed balance. Rejecting a mutation rolls back all participating rows. */
    public synchronized long change(UUID id, long units, Change change) throws SQLException {
        long balance = transaction(() -> { create(id); return update(id, units, change); });
        publish(id, balance);
        return balance;
    }
    public synchronized long transfer(UUID from, UUID to, long units) throws SQLException {
        if (from.equals(to)) throw new Rejected("error.self", "Cannot pay yourself");
        if (units <= 0) throw new Rejected("error.positive", "Amount must be positive");
        long[] balances = transaction(() -> {
            // Acquire row locks in a stable order even across two servers using MySQL.
            if (from.compareTo(to) < 0) { create(from); create(to); } else { create(to); create(from); }
            long remaining = update(from, units, Change.TAKE);
            return new long[]{remaining, update(to, units, Change.GIVE)};
        });
        publish(from, balances[0]);
        publish(to, balances[1]);
        return balances[0];
    }
    private long update(UUID id,long value,Change change) throws SQLException {
        if (value < 0) throw new Rejected("error.negative", "Negative amount");
        String sql;
        switch (change) {
            case TAKE: sql="UPDATE jiujiueco_wallet SET units=units-? WHERE player=? AND units>=?"; break;
            case GIVE: sql="UPDATE jiujiueco_wallet SET units=units+? WHERE player=? AND units<=?"; break;
            default: sql="UPDATE jiujiueco_wallet SET units=? WHERE player=?";
        }
        try (PreparedStatement s=connection().prepareStatement(sql)) {
            s.setLong(1,value); s.setString(2,id.toString());
            if (change != Change.SET) s.setLong(3, change==Change.TAKE ? value : Long.MAX_VALUE-value);
            int changed = s.executeUpdate();
            if (changed == 0 && change != Change.SET && value != 0)
                throw change==Change.TAKE ? new Rejected("error.insufficient", "Insufficient funds") : new Rejected("error.limit", "Balance limit exceeded");
        }
        return balance(id);
    }
    private interface Work<T> { T run() throws SQLException; }
    private <T> T transaction(Work<T> work) throws SQLException {
        Connection active = connection();
        active.setAutoCommit(false);
        inTransaction = true;
        boolean finished = false;
        try {
            T result=work.run(); db.commit(); finished = true; return result;
        } catch (SQLException | RuntimeException failure) {
            try { db.rollback(); finished = true; }
            catch (SQLException rollback) { failure.addSuppressed(rollback); db.close(); }
            throw failure;
        } finally { inTransaction = false; if (finished) active.setAutoCommit(true); }
    }
    @Override public synchronized void close() throws SQLException { closed = true; db.close(); }
}
