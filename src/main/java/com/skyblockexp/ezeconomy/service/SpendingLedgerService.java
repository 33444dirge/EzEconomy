package com.skyblockexp.ezeconomy.service;

import com.skyblockexp.ezeconomy.service.spending.SqlSpendingLedger;
import com.skyblockexp.ezeconomy.api.spending.SpendingLedger;
import com.skyblockexp.ezeconomy.api.storage.EconomyMutationResult;
import com.skyblockexp.ezeconomy.core.EzEconomyPlugin;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** The existing transactional ledger remains the sole writer of balances and accounting rows. */
public final class SpendingLedgerService implements SpendingLedger, AutoCloseable {
    private final EzEconomyPlugin plugin;
    private final Set<String> currencies;
    private volatile boolean active = true;
    private AutoCloseable binding;

    public SpendingLedgerService(EzEconomyPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
        var section = plugin.getConfig().getConfigurationSection("multi-currency.currencies");
        this.currencies = plugin.getConfig().getBoolean("multi-currency.enabled") && section != null
                ? Set.copyOf(section.getKeys(false)) : Set.of(plugin.getDefaultCurrency());
    }

    @Override public boolean available() { return active && plugin.getStorage() != null; }
    @Override public Set<String> currencies() { return currencies; }

    private void requireAvailable() throws SQLException {
        if (!available()) throw new SQLException("消费记账服务已停用或未就绪");
    }

    private void requireCurrency(String currency) throws SQLException {
        requireAvailable();
        if (!currencies.contains(currency)) throw new IllegalArgumentException("未启用的货币: " + currency);
    }

    @Override public void validateSettlement() throws SQLException {
        requireAvailable();
        SqlSpendingLedger.validateSettlement(plugin);
    }

    @Override public synchronized AutoCloseable attach(Object owner, Function<UUID, UUID> subjectOf, String currency) throws SQLException {
        requireCurrency(currency);
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(subjectOf, "subjectOf");
        if (binding != null) throw new IllegalStateException("已有消费归属提供者");
        AutoCloseable registration = SqlSpendingLedger.attach(owner, subjectOf, currency);
        binding = registration;
        return () -> {
            synchronized (SpendingLedgerService.this) {
                registration.close();
                if (binding == registration) binding = null;
            }
        };
    }

    @Override public Snapshot snapshot(int maximum) throws SQLException {
        requireAvailable();
        var result = SqlSpendingLedger.snapshot(maximum);
        return new Snapshot(result.since(), result.rows().stream()
                .map(row -> new Row(row.subject(), row.payer(), row.amount())).toList());
    }

    @Override public void importLegacy(String tablePrefix, String subjectColumn) throws SQLException {
        validateSettlement();
        SqlSpendingLedger.importLegacy(tablePrefix, subjectColumn);
    }

    @Override public BigDecimal balance(UUID payer, String currency) throws SQLException {
        requireCurrency(currency);
        double value = plugin.getStorage().getBalance(Objects.requireNonNull(payer), currency);
        if (!Double.isFinite(value) || value < 0) throw new SQLException("经济余额无效");
        return BigDecimal.valueOf(value);
    }

    @Override public EconomyMutationResult withdraw(String order, UUID payer, String currency, BigDecimal amount) throws SQLException {
        requireCurrency(currency);
        Objects.requireNonNull(payer, "payer");
        double value = exactAmount(amount);
        validateSettlement();
        try (var scope = SqlSpendingLedger.order(order)) {
            return plugin.getStorage().withdrawAndGetBalance(payer, currency, value);
        }
    }

    @Override public EconomyMutationResult refund(String refund, List<String> sources, BigDecimal originalTotal,
                                                  UUID recipient, String currency, BigDecimal amount) throws SQLException {
        requireCurrency(currency);
        validateSettlement();
        return SqlSpendingLedger.refund(refund, List.copyOf(sources), originalTotal,
                Objects.requireNonNull(recipient), currency, exactAmount(amount));
    }

    @Override public EconomyMutationResult withdraw(String order, UUID subject, UUID payer, String currency, BigDecimal amount) throws SQLException {
        requireCurrency(currency);
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(payer, "payer");
        double value = exactAmount(amount);
        validateSettlement();
        try (var scope = SqlSpendingLedger.order(order)) {
            return SqlSpendingLedger.withdrawForSubject(subject, payer, currency, value);
        }
    }

    private static double exactAmount(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() <= 0) throw new IllegalArgumentException("金额必须大于零");
        amount.setScale(8, RoundingMode.UNNECESSARY);
        double value = amount.doubleValue();
        if (!Double.isFinite(value) || BigDecimal.valueOf(value).compareTo(amount) != 0)
            throw new IllegalArgumentException("金额不能被经济后端精确表示");
        return value;
    }

    @Override public synchronized void close() throws Exception {
        active = false;
        if (binding != null) {
            try { binding.close(); } finally { binding = null; }
        }
    }
}
