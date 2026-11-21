package com.skyblockexp.ezeconomy.manager;

import com.skyblockexp.ezeconomy.core.EzEconomyPlugin;
import com.skyblockexp.ezeconomy.storage.DailyRewardStorage;
import com.skyblockexp.ezeconomy.api.storage.StorageProvider;
import java.util.Locale;
import java.util.UUID;
import java.util.Map;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import com.skyblockexp.ezeconomy.util.MessageUtils;

public class DailyRewardManager {
    private final EzEconomyPlugin plugin;
    private final DailyRewardStorage storage;

    public DailyRewardManager(EzEconomyPlugin plugin) {
        this.plugin = plugin;
        this.storage = new DailyRewardStorage(plugin);
    }

    public void handleJoin(Player player) {
        if (!isEnabled()) {
            return;
        }
        if (!player.hasPermission(getPermission())) {
            return;
        }
        StorageProvider storageProvider = plugin.getStorageOrWarn();
        if (storageProvider == null) {
            debug("Storage provider unavailable; skipping daily reward.");
            return;
        }
        double amount = plugin.getConfig().getDouble("daily-reward.amount", 0.0);
        if (amount <= 0.0) {
            debug("Daily reward amount is <= 0; skipping reward.");
            return;
        }
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        long cooldownMillis = getCooldownMillis();
        String currency = resolveCurrency(plugin.getConfig().getString("daily-reward.currency", "default"));
        plugin.submitIo(() -> {
            synchronized (storage) {
                long now = System.currentTimeMillis();
                long lastReward = storage.getLastReward(uuid);
                if (lastReward > now || (lastReward > 0 && now - lastReward < cooldownMillis)) return;
                if (!storageProvider.depositAndGetBalance(uuid, currency, amount).isSuccess()) return;
                storage.setLastReward(uuid, now);
            }
            com.skyblockexp.ezeconomy.util.scheduler.PlatformScheduler.runForPlayer(plugin, player, () -> {
                sendMessage(player, amount, currency);
                playSound(player);
            }, () -> {}, 1L);
            debug("Rewarded " + name + " " + amount + " " + currency + ".");
        });
    }

    public void resetReward(UUID uuid) {
        plugin.submitIo(() -> storage.reset(uuid));
    }

    private boolean isEnabled() {
        return plugin.getConfig().getBoolean("daily-reward.enabled", false);
    }

    private String getPermission() {
        return plugin.getConfig().getString("daily-reward.permission", "ezeconomy.daily");
    }

    private long getCooldownMillis() {
        long hours = plugin.getConfig().getLong("daily-reward.cooldown-hours", 24L);
        if (hours < 0) {
            hours = 0;
        }
        return hours * 60L * 60L * 1000L;
    }

    private String resolveCurrency(String configuredCurrency) {
        String fallback = plugin.getDefaultCurrency();
        if (configuredCurrency == null || configuredCurrency.trim().isEmpty()
                || configuredCurrency.equalsIgnoreCase("default")) {
            return fallback;
        }
        boolean multiEnabled = plugin.getConfig().getBoolean("multi-currency.enabled", false);
        if (!multiEnabled) {
            debug("Multi-currency disabled; using default currency.");
            return fallback;
        }
        String trimmed = configuredCurrency.trim();
        if (currencyExists(trimmed)) {
            return trimmed;
        }
        String lowered = trimmed.toLowerCase(Locale.ROOT);
        if (currencyExists(lowered)) {
            return lowered;
        }
        debug("Configured currency '" + configuredCurrency + "' not found; using default.");
        return fallback;
    }

    private boolean currencyExists(String currency) {
        ConfigurationSection section = plugin.getConfig()
                .getConfigurationSection("multi-currency.currencies." + currency);
        return section != null;
    }

    private String resolveCurrencyDisplay(String currency) {
        boolean multiEnabled = plugin.getConfig().getBoolean("multi-currency.enabled", false);
        if (!multiEnabled) {
            return currency;
        }
        String display = plugin.getConfig()
                .getString("multi-currency.currencies." + currency + ".display");
        return (display == null || display.isEmpty()) ? currency : display;
    }

    private void sendMessage(Player player, double amount, String currency) {
        String messageKey = plugin.getConfig().getString("daily-reward.message-key", "daily_reward_success");
        String formattedAmount = plugin.getCurrencyFormatter().formatShort(amount, null);
        String displayCurrency = resolveCurrencyDisplay(currency);
        MessageUtils.send(player, plugin, messageKey, Map.of("amount", formattedAmount, "currency", displayCurrency));
    }

    private void playSound(Player player) {
        String soundName = plugin.getConfig().getString("daily-reward.sound", "");
        if (soundName == null || soundName.trim().isEmpty()) {
            return;
        }
        try {
            Sound sound = resolveConfiguredSound(soundName);
            if (sound == null) {
                debug("Invalid sound '" + soundName + "' configured for daily reward.");
                return;
            }
            player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
        } catch (NoSuchFieldError e) {
            debug("Invalid sound '" + soundName + "' configured for daily reward.");
        }
    }

    private Sound resolveConfiguredSound(String soundName) {
        String trimmed = soundName.trim();
        String namespaced = trimmed.toLowerCase(Locale.ROOT);

        NamespacedKey key = NamespacedKey.fromString(namespaced);
        if (key != null) {
            Sound byKey = Registry.SOUNDS.get(key);
            if (byKey != null) {
                return byKey;
            }
            if (key.getNamespace().equals(NamespacedKey.MINECRAFT)) {
                Sound byMinecraftKey = Registry.SOUNDS.get(NamespacedKey.minecraft(key.getKey()));
                if (byMinecraftKey != null) {
                    return byMinecraftKey;
                }
            }
        }

        String enumName = trimmed.toUpperCase(Locale.ROOT);
        for (Sound candidate : Sound.values()) {
            if (candidate.name().equals(enumName)) {
                return candidate;
            }
        }

        return null;
    }

    private void debug(String message) {
        if (!plugin.isDebugEnabled()) {
            return;
        }
        plugin.getLogger().info("[DailyReward] " + message);
    }
}
