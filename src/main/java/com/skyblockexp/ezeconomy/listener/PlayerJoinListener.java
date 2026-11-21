package com.skyblockexp.ezeconomy.listener;

import com.skyblockexp.ezeconomy.core.EzEconomyPlugin;
import com.skyblockexp.ezeconomy.api.storage.StorageProvider;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import java.util.Map;
import java.util.UUID;

public class PlayerJoinListener implements Listener {
    private final EzEconomyPlugin plugin;

    public PlayerJoinListener(EzEconomyPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {

        var player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        String displayName = player.getDisplayName();
        String currency = plugin.getDefaultCurrency();
        boolean ensureAccount = plugin.getConfig().getBoolean("store-on-join.enabled", false);
        StorageProvider storage = plugin.getStorageOrWarn();
        if (storage != null) plugin.submitIo(() -> {
            storage.persistPlayerInfo(uuid, name, displayName);
            if (ensureAccount) storage.depositAndGetBalance(uuid, currency, 0.0);
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
