package com.skyblockexp.ezeconomy.bootstrap.component;

import com.skyblockexp.ezeconomy.bootstrap.BootstrapComponent;
import com.skyblockexp.ezeconomy.core.EzEconomyPlugin;
import com.skyblockexp.ezeconomy.cache.CacheManager;
import com.skyblockexp.ezeconomy.cache.CachingStrategy;

/**
 * Bootstrap component that configures the global cache strategy early during startup.
 */
public class CacheComponent implements BootstrapComponent {
    private final EzEconomyPlugin plugin;
    private com.skyblockexp.ezeconomy.util.scheduler.ScheduledTask maintenance;

    public CacheComponent(EzEconomyPlugin plugin) { this.plugin = plugin; }

    @Override
    public void start() {
        stop();
        maintenance = com.skyblockexp.ezeconomy.util.scheduler.PlatformScheduler.runTaskTimer(plugin, () -> {
            CacheManager.cleanUp();
            com.skyblockexp.ezeconomy.storage.TransferLockManager.cleanUp();
        }, 1200L, 1200L);
        String caching = plugin.getConfig().getString("caching-strategy", plugin.getConfig().getString("locking-strategy", "LOCAL")).toUpperCase();
        try {
            CacheManager.setStrategy(CachingStrategy.valueOf(caching));
            plugin.getLogger().info("Cache strategy set to " + caching + " (provider=" + CacheManager.getProviderClassName() + ")");
        } catch (IllegalArgumentException ia) {
            CacheManager.setStrategy(CachingStrategy.LOCAL);
            plugin.getLogger().warning("Unknown caching-strategy '" + caching + "', defaulting to LOCAL (provider=" + CacheManager.getProviderClassName() + ")");
        }
    }

    @Override
    public void stop() {
        if (maintenance != null) { maintenance.cancel(); maintenance = null; }
        CacheManager.close();
    }

    @Override
    public void reload() {
        stop(); start();
    }
}
