package com.skyblockexp.ezeconomy.bootstrap.component;

import com.skyblockexp.ezeconomy.bootstrap.BootstrapComponent;
import com.skyblockexp.ezeconomy.core.EzEconomyPlugin;
import com.skyblockexp.ezeconomy.service.EconomyIoExecutor;

public final class IoComponent implements BootstrapComponent {
    private final EzEconomyPlugin plugin;
    private EconomyIoExecutor executor;
    public IoComponent(EzEconomyPlugin plugin) { this.plugin = plugin; }
    @Override public void start() {
        executor = new EconomyIoExecutor(plugin.getLogger(), plugin.getConfig().getInt("performance.io.workers", 2),
                plugin.getConfig().getInt("performance.io.queue-capacity", 1024));
        plugin.setIoExecutor(executor);
    }
    @Override public void stop() {
        plugin.setIoExecutor(null);
        if (executor != null) { executor.close(); executor = null; }
    }
    @Override public void reload() { stop(); start(); }
}
