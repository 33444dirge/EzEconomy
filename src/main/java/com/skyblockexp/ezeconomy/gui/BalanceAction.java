package com.skyblockexp.ezeconomy.gui;

import org.bukkit.entity.Player;

import com.skyblockexp.ezeconomy.core.EzEconomyPlugin;
import com.skyblockexp.ezeconomy.api.storage.StorageProvider;
import java.util.HashMap;
import java.util.Map;

public class BalanceAction extends GuiAction {
    public BalanceAction() {
        super("balance", "\u00A7aBalance");
    }

    @Override
    public void open(EzEconomyPlugin plugin, Player player) {
        StorageProvider storage = plugin.getStorageOrWarn();
        if (storage == null) {
            return;
        }
        var config = plugin.getConfig();
        var uuid = player.getUniqueId();
        var section = config.getConfigurationSection("multi-currency.currencies");
        boolean multi = config.getBoolean("multi-currency.enabled", false) && section != null && !section.getKeys(false).isEmpty();
        var selected = multi ? java.util.List.copyOf(section.getKeys(false)) : java.util.List.of(plugin.getDefaultCurrency());
        if (!plugin.submitIo(() -> load(plugin, player, storage, uuid, selected))) {
            com.skyblockexp.ezeconomy.util.MessageUtils.sendRaw(player, plugin, "Balance queries are busy; please try again.");
        }
    }

    private void load(EzEconomyPlugin plugin, Player player, StorageProvider storage, java.util.UUID uuid,
                      java.util.List<String> selected) {
        Map<String, Double> currencies = new HashMap<>();
        for (String currency : selected) currencies.put(currency, storage.getBalance(uuid, currency));
        com.skyblockexp.ezeconomy.util.scheduler.PlatformScheduler.runForPlayer(plugin, player,
                () -> BalanceGui.open(plugin, player, currencies), () -> {}, 1L);
    }
}
