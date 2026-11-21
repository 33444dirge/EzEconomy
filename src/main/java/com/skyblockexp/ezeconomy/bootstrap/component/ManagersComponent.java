package com.skyblockexp.ezeconomy.bootstrap.component;

import com.skyblockexp.ezeconomy.bootstrap.BootstrapComponent;
import com.skyblockexp.ezeconomy.core.EzEconomyPlugin;

public class ManagersComponent implements BootstrapComponent {
    private final EzEconomyPlugin plugin;
    private com.skyblockexp.ezeconomy.util.scheduler.ScheduledTask flowCleanup;

    public ManagersComponent(EzEconomyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void start() {
        initManagers();
    }

    @Override
    public void stop() {
        if (flowCleanup != null) { flowCleanup.cancel(); flowCleanup = null; }
        if (plugin.getPayFlowManager() != null) plugin.getPayFlowManager().clear();
    }

    @Override
    public void reload() {
        // Re-initialize managers in-place; keep lifecycle inside this component
        initManagers();
    }

    private void initManagers() {
        stop();
        // Initialize managers previously owned by the main plugin class
        com.skyblockexp.ezeconomy.manager.CurrencyPreferenceManager pref = new com.skyblockexp.ezeconomy.manager.CurrencyPreferenceManager(plugin);
        com.skyblockexp.ezeconomy.manager.CurrencyManager cm = new com.skyblockexp.ezeconomy.manager.CurrencyManager(plugin);
        com.skyblockexp.ezeconomy.gui.PayFlowManager pfm = new com.skyblockexp.ezeconomy.gui.PayFlowManager();

        plugin.setCurrencyPreferenceManager(pref);
        plugin.setCurrencyManager(cm);
        plugin.setPayFlowManager(pfm);
        flowCleanup = com.skyblockexp.ezeconomy.util.scheduler.PlatformScheduler.runTaskTimer(plugin, pfm::cleanUp, 600L, 600L);
    }
}
