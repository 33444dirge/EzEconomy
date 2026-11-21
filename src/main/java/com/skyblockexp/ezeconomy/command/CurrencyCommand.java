package com.skyblockexp.ezeconomy.command;

import com.skyblockexp.ezeconomy.core.EzEconomyPlugin;
import com.skyblockexp.ezeconomy.util.MessageUtils;
import java.util.Locale;
import java.util.Map;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Lists wallet currencies and selects a display preference; never moves funds. */
public final class CurrencyCommand implements CommandExecutor {
    private final EzEconomyPlugin plugin;
    public CurrencyCommand(EzEconomyPlugin plugin) { this.plugin = plugin; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!plugin.getConfig().getBoolean("multi-currency.enabled", false)) {
            MessageUtils.send(sender, plugin, "multi_currency_disabled");
            return true;
        }
        if (!(sender instanceof Player player)) {
            MessageUtils.send(sender, plugin, "only_players");
            return true;
        }
        if (args.length > 1) {
            MessageUtils.send(sender, plugin, "usage_currency");
            return true;
        }
        var section = plugin.getConfig().getConfigurationSection("multi-currency.currencies");
        var currencies = section == null ? java.util.Set.<String>of() : section.getKeys(false);
        var preferences = plugin.getCurrencyPreferenceManager();
        if (args.length == 0) {
            MessageUtils.send(sender, plugin, "preferred_currency", Map.of("currency", preferences.getPreferredCurrency(player.getUniqueId())));
            MessageUtils.send(sender, plugin, "available_currencies");
            currencies.forEach(currency -> sender.sendMessage(" - " + currency));
            return true;
        }
        String currency = args[0].toLowerCase(Locale.ROOT);
        if (!currencies.contains(currency)) {
            MessageUtils.send(sender, plugin, "unknown_currency", Map.of("currency", currency));
            return true;
        }
        preferences.setPreferredCurrency(player.getUniqueId(), currency);
        MessageUtils.send(sender, plugin, "set_currency", Map.of("currency", currency));
        return true;
    }
}
