package com.skyblockexp.ezeconomy.tabcomplete;

import com.skyblockexp.ezeconomy.core.EzEconomyPlugin;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

public final class CurrencyTabCompleter implements TabCompleter {
    private final EzEconomyPlugin plugin;
    public CurrencyTabCompleter(EzEconomyPlugin plugin) { this.plugin = plugin; }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1 || !(sender.hasPermission("ezeconomy.user.currency") || sender.hasPermission("ezeconomy.currency"))) return List.of();
        var section = plugin.getConfig().getConfigurationSection("multi-currency.currencies");
        if (section == null) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return section.getKeys(false).stream().filter(key -> key.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }
}
