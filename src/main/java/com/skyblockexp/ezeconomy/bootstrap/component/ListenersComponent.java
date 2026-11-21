package com.skyblockexp.ezeconomy.bootstrap.component;

import com.skyblockexp.ezeconomy.bootstrap.BootstrapComponent;
import com.skyblockexp.ezeconomy.core.EzEconomyPlugin;
import com.skyblockexp.ezeconomy.listener.PlayerJoinListener;
import com.skyblockexp.ezeconomy.gui.GuiListener;
import com.skyblockexp.ezeconomy.listener.PlayerLookupListener;
import org.bukkit.Bukkit;

public class ListenersComponent implements BootstrapComponent {
    private final EzEconomyPlugin plugin;
    private final java.util.List<org.bukkit.event.Listener> listeners = new java.util.ArrayList<>();

    public ListenersComponent(EzEconomyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void start() {
        stop();
        register(new PlayerJoinListener(plugin));
        // Keep PlayerLookup cache in sync with player activity
        register(new PlayerLookupListener());
        register(new GuiListener(plugin));
    }

    private void register(org.bukkit.event.Listener listener) {
        listeners.add(listener);
        Bukkit.getPluginManager().registerEvents(listener, plugin);
    }

    @Override
    public void stop() {
        listeners.forEach(org.bukkit.event.HandlerList::unregisterAll);
        listeners.clear();
        com.skyblockexp.ezeconomy.util.PlayerLookup.clearCache();
    }

    @Override
    public void reload() {
        // No-op
    }
}
