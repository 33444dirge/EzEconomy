package com.skyblockexp.ezeconomy.core;

import com.skyblockexp.ezeconomy.api.EzEconomyAPI;
import com.skyblockexp.ezeconomy.api.storage.EconomyMutationResult;
import com.skyblockexp.ezeconomy.api.storage.StorageProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.OfflinePlayer;

/**
 * Vault Economy implementation for EzEconomy.
 */
public class VaultEconomyImpl implements Economy {
    private static final String INSUFFICIENT_FUNDS = "Insufficient funds";
    private final EzEconomyPlugin plugin;
    private final EzEconomyAPI api;
    private final boolean fastVaultMutationsEnabled;
    private final long fastVaultMutationShutdownTimeoutMs;
    private final Map<String, Double> fastBalances = new ConcurrentHashMap<>();
    private final com.skyblockexp.ezeconomy.storage.mysql.StripedLockManager fastBalanceLocks =
            new com.skyblockexp.ezeconomy.storage.mysql.StripedLockManager(256);
    private volatile boolean closed;
    private final Map<String, PendingPlayerBalanceWrite> pendingPlayerWrites = new ConcurrentHashMap<>();
    private final Object fastMutationExecutorLock = new Object();
    private volatile ExecutorService fastMutationExecutor;

    public VaultEconomyImpl(EzEconomyPlugin plugin) {
        this.plugin = plugin;
        this.api = new EzEconomyAPI(plugin.getStorage());
        FileConfiguration config = getConfigSafely();
        this.fastVaultMutationsEnabled = isFastVaultMutationConfigEnabled(config);
        this.fastVaultMutationShutdownTimeoutMs = config == null
                ? 15000L
                : config.getLong("performance.fast-vault-mutations.shutdown-flush-timeout-ms", 15000L);
    }

    // ----------------------------------------------------------------------
    // Economy metadata
    // ----------------------------------------------------------------------

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        StorageProvider storage = getStorageProvider();
        if (storage == null) {
            warnStorage("checking account for", player.getName());
            return false;
        }
        try {
            storage.getBalance(player.getUniqueId(), plugin.getDefaultCurrency());
            return true;
        } catch (Exception ex) {
            plugin.getLogger().warning("Exception when checking account for " + player.getName() + ": " + ex.getMessage());
            return false;
        }
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return hasAccount(playerName);
    }

    public Object getStorage() {
        return plugin.getStorage();
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return hasAccount(player);
    }

    @Override
    public boolean isEnabled() {
        return !closed && plugin.isEnabled();
    }

    @Override
    public String getName() {
        return "EzEconomy";
    }

    @Override public boolean hasBankSupport() { return false; }

    @Override
    public int fractionalDigits() {
        return 2;
    }

    @Override
    public String format(double amount) {
        return plugin.getCurrencyFormatter().format(amount);
    }

    @Override
    public String currencyNamePlural() {
        return "Dollars";
    }

    @Override
    public String currencyNameSingular() {
        return "Dollar";
    }

    @Override
    public boolean hasAccount(String playerName) {
        return true;
    }

    @Override
    public double getBalance(String playerName) {
        return getBalance(plugin.getServer().getOfflinePlayer(playerName));
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        StorageProvider storage = getStorageProvider();
        if (storage == null) return 0.0;
        if (canUseFastVaultMutations(storage)) {
            Double fastBalance = fastBalances.get(balanceKey(player.getUniqueId(), plugin.getDefaultCurrency()));
            if (fastBalance != null) {
                return fastBalance.doubleValue();
            }
        }
        return api.getBalance(player.getUniqueId(), plugin.getDefaultCurrency()).getBalance();
    }

    // ----------------------------------------------------------------------
    // Player funds
    // ----------------------------------------------------------------------

    @Override
    public boolean has(String playerName, double amount) {
        return has(plugin.getServer().getOfflinePlayer(playerName), amount);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        StorageProvider storage = getStorageProvider();
        if (storage == null) {
            warnStorage("checking funds for", player.getName());
            return false;
        }
        if (canUseFastVaultMutations(storage)) {
            Double fastBalance = fastBalances.get(balanceKey(player.getUniqueId(), plugin.getDefaultCurrency()));
            if (fastBalance != null) {
                return fastBalance.doubleValue() >= amount;
            }
        }
        try {
            return storage.getBalance(player.getUniqueId(), plugin.getDefaultCurrency()) >= amount;
        } catch (Exception ex) {
            plugin.getLogger().warning("Exception when checking funds for " + player.getName() + ": " + ex.getMessage());
            return false;
        }
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        OfflinePlayer player = plugin.getServer().getOfflinePlayer(playerName);
        return withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        return withdrawPlayer(player, amount, plugin.getDefaultCurrency());
    }

    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount, String currency) {
        StorageProvider storage = getStorageProvider();
        if (storage == null) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Storage unavailable");
        }
        UUID uuid = player.getUniqueId();
        if (canUseFastVaultMutations(storage)) {
            EconomyMutationResult result = mutateFastPlayerBalance(storage, uuid, currency, amount, true);
            boolean success = result.isSuccess();
            double balance = result.getBalance();
            return success
                    ? new EconomyResponse(amount, balance, EconomyResponse.ResponseType.SUCCESS, null)
                    : new EconomyResponse(0, balance, EconomyResponse.ResponseType.FAILURE, INSUFFICIENT_FUNDS);
        }
        EconomyMutationResult result = storage.withdrawAndGetBalance(uuid, currency, amount);
        boolean success = result.isSuccess();
        double balance = result.getBalance();
        return success
                ? new EconomyResponse(amount, balance, EconomyResponse.ResponseType.SUCCESS, null)
                : new EconomyResponse(0, balance, EconomyResponse.ResponseType.FAILURE, INSUFFICIENT_FUNDS);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        OfflinePlayer player = plugin.getServer().getOfflinePlayer(playerName);
        return depositPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        return depositPlayer(player, amount, plugin.getDefaultCurrency());
    }

    public EconomyResponse depositPlayer(OfflinePlayer player, double amount, String currency) {
        StorageProvider storage = getStorageProvider();
        if (storage == null) {
            return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, "Storage unavailable");
        }
        if (canUseFastVaultMutations(storage)) {
            EconomyMutationResult result = mutateFastPlayerBalance(storage, player.getUniqueId(), currency, amount, false);
            double balance = result.getBalance();
            return result.isSuccess()
                    ? new EconomyResponse(amount, balance, EconomyResponse.ResponseType.SUCCESS, null)
                    : new EconomyResponse(0, balance, EconomyResponse.ResponseType.FAILURE, "Deposit failed");
        }
        EconomyMutationResult result = storage.depositAndGetBalance(player.getUniqueId(), currency, amount);
        boolean success = result.isSuccess();
        double balance = result.getBalance();
        return success
                ? new EconomyResponse(amount, balance, EconomyResponse.ResponseType.SUCCESS, null)
                : new EconomyResponse(0, balance, EconomyResponse.ResponseType.FAILURE, "Deposit failed");
    }

    // Vault requires these unsupported methods.
    @Override public EconomyResponse createBank(String name, String player) { return notSupported(); }

    @Override public EconomyResponse createBank(String name, OfflinePlayer player) { return notSupported(); }

    @Override public EconomyResponse deleteBank(String name) { return notSupported(); }

    @Override public EconomyResponse bankBalance(String name) { return notSupported(); }

    @Override public EconomyResponse bankHas(String name, double amount) { return notSupported(); }

    @Override public EconomyResponse bankWithdraw(String name, double amount) { return notSupported(); }

    @Override public EconomyResponse bankDeposit(String name, double amount) { return notSupported(); }

    @Override public EconomyResponse isBankOwner(String name, String player) { return notSupported(); }

    @Override public EconomyResponse isBankOwner(String name, OfflinePlayer player) { return notSupported(); }

    @Override public EconomyResponse isBankMember(String name, String player) { return notSupported(); }

    @Override public EconomyResponse isBankMember(String name, OfflinePlayer player) { return notSupported(); }

    @Override public List<String> getBanks() { return List.of(); }

    private EconomyResponse notSupported() {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Bank support not implemented");
    }

    private StorageProvider getStorageProvider() {
        if (closed) return null;
        Object storage = getStorage();
        return (storage instanceof StorageProvider provider) ? provider : null;
    }

    private void warnStorage(String action, String target) {
        plugin.getLogger().warning("Storage unavailable when " + action + " " + target);
    }

    private boolean canUseFastVaultMutations(StorageProvider storage) {
        return !closed && storage != null && fastVaultMutationsEnabled && plugin.getLockManager() == null;
    }

    private FileConfiguration getConfigSafely() {
        try {
            return plugin.getConfig();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean isFastVaultMutationConfigEnabled(FileConfiguration config) {
        if (config == null) {
            return false;
        }
        if (!config.getBoolean("performance.fast-vault-mutations.enabled", true)) {
            return false;
        }
        if (!config.getBoolean("performance.balance-cache.enabled", true)) {
            return false;
        }
        String strategy = config.getString("caching-strategy", config.getString("locking-strategy", "LOCAL"));
        return strategy == null || "LOCAL".equalsIgnoreCase(strategy.trim());
    }

    private EconomyMutationResult mutateFastPlayerBalance(StorageProvider storage, UUID uuid, String currency, double amount, boolean withdraw) {
        String key = balanceKey(uuid, currency);
        synchronized (getFastBalanceLock(key)) {
            Double currentFast = fastBalances.get(key);
            if (closed || (currentFast == null && fastBalances.size() >= 16_384)) {
                return EconomyMutationResult.failure(0.0, "Fast mutation queue unavailable");
            }
            double current = currentFast != null ? currentFast.doubleValue() : storage.getBalance(uuid, currency);
            if (withdraw && current < amount) {
                return EconomyMutationResult.failure(current, INSUFFICIENT_FUNDS);
            }
            double updated = withdraw ? current - amount : current + amount;
            fastBalances.put(key, updated);
            if (!enqueuePlayerBalanceWrite(key, storage, uuid, currency, updated)) {
                if (currentFast == null) fastBalances.remove(key); else fastBalances.put(key, currentFast);
                return EconomyMutationResult.failure(current, "Fast mutation queue unavailable");
            }
            return EconomyMutationResult.success(updated);
        }
    }

    private Object getFastBalanceLock(String key) {
        return fastBalanceLocks.lockFor(key);
    }

    private String balanceKey(UUID uuid, String currency) {
        return uuid.toString() + ':' + currency;
    }

    private boolean enqueuePlayerBalanceWrite(String key, StorageProvider storage, UUID uuid, String currency, double balance) {
        synchronized (fastMutationExecutorLock) {
            if (closed) return false;
            PendingPlayerBalanceWrite write = new PendingPlayerBalanceWrite(storage, uuid, currency, balance);
            PendingPlayerBalanceWrite previous = pendingPlayerWrites.put(key, write);
            if (previous == null) {
                try { getFastMutationExecutor().execute(() -> flushPlayerBalanceWrite(key)); }
                catch (java.util.concurrent.RejectedExecutionException full) {
                    pendingPlayerWrites.remove(key, write);
                    return false;
                }
            }
            return true;
        }
    }

    private void flushPlayerBalanceWrite(String key) {
        synchronized (getFastBalanceLock(key)) {
            PendingPlayerBalanceWrite write = pendingPlayerWrites.remove(key);
            if (write == null) return;
            try {
                write.storage.setBalance(write.uuid, write.currency, write.balance);
                fastBalances.remove(key);
            } catch (RuntimeException failure) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE,
                        "Fast Vault balance flush failed for " + key + "; review cached balance " + write.balance, failure);
            }
        }
    }

    private ExecutorService getFastMutationExecutor() {
        ExecutorService executor = fastMutationExecutor;
        if (executor != null) {
            return executor;
        }
        synchronized (fastMutationExecutorLock) {
            if (fastMutationExecutor == null) {
                fastMutationExecutor = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                        new java.util.concurrent.ArrayBlockingQueue<>(4096), new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable runnable) {
                        Thread thread = new Thread(runnable, "EzEconomy-Vault-FastMutations");
                        thread.setDaemon(true);
                        return thread;
                    }
                }, new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
            }
            return fastMutationExecutor;
        }
    }

    public void shutdown() {
        ExecutorService executor;
        synchronized (fastMutationExecutorLock) {
            executor = fastMutationExecutor;
            closed = true;
        }
        if (executor == null) {
            fastBalances.clear();
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(Math.max(1L, fastVaultMutationShutdownTimeoutMs), TimeUnit.MILLISECONDS)) {
                plugin.getLogger().warning("[EzEconomy] Timed out waiting for fast Vault balance flush; forcing shutdown.");
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private static final class PendingPlayerBalanceWrite {
        private final StorageProvider storage;
        private final UUID uuid;
        private final String currency;
        private final double balance;

        private PendingPlayerBalanceWrite(StorageProvider storage, UUID uuid, String currency, double balance) {
            this.storage = storage;
            this.uuid = uuid;
            this.currency = currency;
            this.balance = balance;
        }
    }

    // --- Account creation (no-op) ---
    @Override public boolean createPlayerAccount(String playerName) { return true; }
    @Override public boolean createPlayerAccount(OfflinePlayer player) { return true; }
    @Override public boolean createPlayerAccount(String playerName, String worldName) { return true; }
    @Override public boolean createPlayerAccount(OfflinePlayer player, String worldName) { return true; }

    // --- World support (not implemented) ---
    @Override public double getBalance(String playerName, String world) { return getBalance(playerName); }
    @Override public double getBalance(OfflinePlayer player, String world) { return getBalance(player); }
    @Override public boolean has(String playerName, String worldName, double amount) { return has(playerName, amount); }
    @Override public boolean has(OfflinePlayer player, String worldName, double amount) { return has(player, amount); }
    @Override public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) { return withdrawPlayer(playerName, amount); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) { return withdrawPlayer(player, amount); }
    @Override public EconomyResponse depositPlayer(String playerName, String worldName, double amount) { return depositPlayer(playerName, amount); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) { return depositPlayer(player, amount); }

}
