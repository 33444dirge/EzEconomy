package com.skyblockexp.ezeconomy.listener;

import com.skyblockexp.ezeconomy.core.EzEconomyPlugin;
import com.skyblockexp.ezeconomy.manager.DailyRewardManager;
import com.skyblockexp.ezeconomy.api.storage.StorageProvider;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import java.util.Map;
import java.util.UUID;

public class PlayerJoinListener implements Listener {
    private final EzEconomyPlugin plugin;
    private final DailyRewardManager manager;

    public PlayerJoinListener(EzEconomyPlugin plugin, DailyRewardManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        manager.handleJoin(event.getPlayer());

        var player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        String displayName = player.getDisplayName();
        String currency = plugin.getDefaultCurrency();
        boolean ensureAccount = plugin.getConfig().getBoolean("store-on-join.enabled", false);
        boolean createBank = plugin.getConfig().getBoolean("banking.enabled", true)
                && plugin.getConfig().getBoolean("banking.auto-create-on-join", true);
        StorageProvider storage = plugin.getStorageOrWarn();
        if (storage != null) plugin.submitIo(() -> {
            storage.persistPlayerInfo(uuid, name, displayName);
            if (ensureAccount) storage.depositAndGetBalance(uuid, currency, 0.0);
            if (createBank && !storage.bankExists(name)) storage.createBank(name, uuid);
        });
        deliverPendingNotifications(player);
    }

    private void deliverPendingNotifications(org.bukkit.entity.Player player) {
        com.skyblockexp.ezeconomy.messaging.MessagingService ms = plugin.getMessagingService();
        if (ms == null) return;
        if (!plugin.getConfig().getBoolean("cross-server.enabled", false)) return;
        com.skyblockexp.ezeconomy.util.scheduler.PlatformScheduler.runForPlayer(plugin, player, () -> {
            ms.deliverPendingNotifications(player);
        }, () -> {}, 40L);
    }
}
