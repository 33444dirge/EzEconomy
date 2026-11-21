package com.skyblockexp.ezeconomy.manager;

import com.skyblockexp.ezeconomy.core.EzEconomyPlugin;
import com.skyblockexp.ezeconomy.util.scheduler.PlatformScheduler;
import com.skyblockexp.ezeconomy.util.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class BankInterestManager {
    private final EzEconomyPlugin plugin;
    private ScheduledTask task = null;

    private final java.util.concurrent.atomic.AtomicBoolean inFlight = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean stopped;

    public BankInterestManager(EzEconomyPlugin plugin) {
        this.plugin = plugin;
    }

    public void start(long intervalTicks) {
        if (task != null) {
            task.cancel();
        }
        stopped = false;
        long period = Math.max(1L, intervalTicks);
        task = PlatformScheduler.runTaskTimer(plugin, () -> {
            if (!stopped && inFlight.compareAndSet(false, true)) {
                if (!plugin.submitIo(() -> {
                    try { if (!stopped) payInterestToAll(); }
                    finally { inFlight.set(false); }
                })) inFlight.set(false);
            }
        }, period, period);
    }

    public void stop() {
        stopped = true;
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void payInterestToAll() {
        com.skyblockexp.ezeconomy.api.storage.StorageProvider storage = plugin.getStorageOrWarn();
        if (storage == null) {
            return;
        }
        org.bukkit.configuration.file.FileConfiguration config = plugin.getConfig();
        boolean multiEnabled = config.getBoolean("multi-currency.enabled", false);
        Set<String> currencies;
        if (multiEnabled) {
            var section = config.getConfigurationSection("multi-currency.currencies");
            currencies = section != null ? section.getKeys(false) : java.util.Collections.singleton("dollar");
        } else {
            currencies = java.util.Collections.singleton("dollar");
        }
        java.util.Set<String> banks = storage.getBanks();
        for (String currency : currencies) {
            for (String bank : banks) {
                if (stopped) return;
                double bankBalance = storage.getBankBalance(bank, currency);
                Set<UUID> members = storage.getBankMembers(bank);
                if (members == null || members.isEmpty()) continue;
                double grossInterest = calculateInterest(bankBalance);
                double perMemberInterest = grossInterest / members.size();
                for (UUID uuid : members) {
                    OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
                    if (perMemberInterest > 0) {
                        if (!storage.depositAndGetBalance(uuid, currency, perMemberInterest).isSuccess()) continue;
                        if (player.isOnline() && player.getPlayer() != null) {
                            String compact = plugin.getCurrencyFormatter().formatShort(perMemberInterest, null);
                            org.bukkit.entity.Player recipient = player.getPlayer();
                            PlatformScheduler.runForPlayer(plugin, recipient, () -> recipient.sendMessage("You received " + compact + " " + currency + " interest from bank '" + bank + "'"), () -> {}, 1L);
                        }
                    }
                }
            }
        }
    }

    // Example interest calculation (1% per payout)
    private double calculateInterest(double balance) {
        return Math.round(balance * 0.01 * 100.0) / 100.0;
    }
}
