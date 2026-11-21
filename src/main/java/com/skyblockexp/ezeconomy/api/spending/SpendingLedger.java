package com.skyblockexp.ezeconomy.api.spending;

import com.skyblockexp.ezeconomy.api.storage.EconomyMutationResult;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Persistent consumption accounting, published through Bukkit's ServicesManager.
 * The caller supplies opaque accounting subjects; this API never discovers memberships,
 * resolves names, sorts rankings, schedules refreshes, or opens menus.
 * All methods except available/currencies must run on the caller's bounded IO worker.
 * Keep the registration until shutdown; closing it detaches attribution without deleting history.
 */
public interface SpendingLedger {
    record Row(UUID subject, UUID payer, BigDecimal amount) {}
    record Snapshot(long since, List<Row> rows) {
        public Snapshot { rows = List.copyOf(rows); }
    }

    boolean available();
    Set<String> currencies();

    /** Validate the live backend without relying on plugin versions or private fields in consumers. */
    void validateSettlement() throws SQLException;

    /** Resolver belongs to the caller and must be thread-safe and free of Bukkit/world access. */
    AutoCloseable attach(Object owner, Function<UUID, UUID> subjectOf, String currency) throws SQLException;

    /** One-time, transactional adoption of a previous ledger schema supplied by the caller. */
    void importLegacy(String tablePrefix, String subjectColumn) throws SQLException;

    /** Returns accounting rows; exceeding the bound fails instead of returning an incomplete ranking. */
    Snapshot snapshot(int maximum) throws SQLException;

    BigDecimal balance(UUID payer, String currency) throws SQLException;
    EconomyMutationResult withdraw(String order, UUID payer, String currency, BigDecimal amount) throws SQLException;
    /** Explicit immutable attribution, even before an optional snapshot consumer has attached. */
    EconomyMutationResult withdraw(String order, UUID subject, UUID payer, String currency, BigDecimal amount) throws SQLException;
    EconomyMutationResult refund(String refund, List<String> sources, BigDecimal originalTotal,
                                 UUID recipient, String currency, BigDecimal amount) throws SQLException;
}
