package com.skyblockexp.ezeconomy.command.eco;

import com.skyblockexp.ezeconomy.command.Subcommand;
import com.skyblockexp.ezeconomy.core.EzEconomyPlugin;
import com.skyblockexp.ezeconomy.gui.BalanceAction;
import com.skyblockexp.ezeconomy.util.MessageUtils;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Uses the GUI's bounded balance query instead of duplicating database loops. */
public class GuiSubcommand implements Subcommand {
    private final EzEconomyPlugin plugin;
    public GuiSubcommand(EzEconomyPlugin plugin) { this.plugin = plugin; }
    @Override public boolean execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtils.send(sender, plugin, "only_players");
        } else if (!player.hasPermission("ezeconomy.user.eco.gui") && !player.hasPermission("ezeconomy.eco.gui")) {
            MessageUtils.send(sender, plugin, "no_permission");
        } else if (plugin.getStorageOrWarn() == null) {
            MessageUtils.send(sender, plugin, "storage_unavailable");
        } else new BalanceAction().open(plugin, player);
        return true;
    }
}
