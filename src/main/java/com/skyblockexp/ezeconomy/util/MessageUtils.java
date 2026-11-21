package com.skyblockexp.ezeconomy.util;

import com.skyblockexp.ezeconomy.core.EzEconomyPlugin;
import com.skyblockexp.ezeconomy.core.MessageProvider;
import java.util.Map;
import org.bukkit.command.CommandSender;

public final class MessageUtils {

    private MessageUtils() {}

    public static void send(CommandSender sender, EzEconomyPlugin plugin, String key) {
        send(sender, plugin, key, Map.of());
    }

    public static void send(CommandSender sender, EzEconomyPlugin plugin, String key, Map<String, String> placeholders) {
        MessageProvider messages = provider(plugin);
        if (messages == null) {
            sendRaw(sender, plugin, "[EzEconomy] Message system not initialized.");
            return;
        }
        sendRaw(sender, plugin, messages.get(key, placeholders));
    }

    public static void sendRaw(CommandSender sender, EzEconomyPlugin plugin, String message) {
        if (plugin != null && sender instanceof org.bukkit.entity.Player player
                && com.skyblockexp.ezeconomy.util.scheduler.PlatformScheduler.isFolia()
                && !org.bukkit.Bukkit.isOwnedByCurrentRegion(player)) {
            com.skyblockexp.ezeconomy.util.scheduler.PlatformScheduler.runForPlayer(plugin, player,
                    () -> player.sendMessage(message), () -> {}, 1L);
        } else sender.sendMessage(message);
    }

    public static String format(EzEconomyPlugin plugin, String key) {
        return format(plugin, key, Map.of());
    }

    public static String format(EzEconomyPlugin plugin, String key, Map<String, String> placeholders) {
        MessageProvider messages = provider(plugin);
        if (messages == null) {
            return "[EzEconomy] Message system not initialized.";
        }
        return messages.get(key, placeholders);
    }

    public static String color(EzEconomyPlugin plugin, String raw) {
        MessageProvider messages = provider(plugin);
        if (messages == null) {
            return raw;
        }
        return messages.color(raw);
    }

    private static MessageProvider provider(EzEconomyPlugin plugin) {
        return plugin == null ? null : plugin.getMessageProvider();
    }
}
