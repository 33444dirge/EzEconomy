package com.skyblockexp.ezeconomy.bootstrap.component;

import com.skyblockexp.ezeconomy.api.spending.SpendingLedger;
import com.skyblockexp.ezeconomy.bootstrap.BootstrapComponent;
import com.skyblockexp.ezeconomy.core.EzEconomyPlugin;
import com.skyblockexp.ezeconomy.service.SpendingLedgerService;
import org.bukkit.plugin.ServicePriority;

public final class SpendingLedgerComponent implements BootstrapComponent {
    private final EzEconomyPlugin plugin;
    private SpendingLedgerService service;

    public SpendingLedgerComponent(EzEconomyPlugin plugin) { this.plugin = plugin; }

    @Override public void start() {
        service = new SpendingLedgerService(plugin);
        plugin.getServer().getServicesManager().register(SpendingLedger.class, service, plugin, ServicePriority.Normal);
    }

    @Override public void stop() {
        if (service == null) return;
        plugin.getServer().getServicesManager().unregister(SpendingLedger.class, service);
        try { service.close(); }
        catch (Exception failure) { plugin.getLogger().warning("消费记账接口解绑失败: " + failure.getMessage()); }
        finally { service = null; }
    }

    @Override public void reload() { stop(); start(); }
}
