package com.skyblockexp.ezeconomy.bootstrap;

import com.skyblockexp.ezeconomy.bootstrap.component.*;
import com.skyblockexp.ezeconomy.core.EzEconomyPlugin;
import java.util.ArrayList;
import java.util.List;

/** Starts dependencies in order and releases only owned, active components in reverse order. */
public class Bootstrap {
    private final EzEconomyPlugin plugin;
    private final List<BootstrapComponent> components;
    private final List<BootstrapComponent> active = new ArrayList<>();

    public Bootstrap(EzEconomyPlugin plugin) {
        this.plugin = plugin;
        components = List.of(new ConfigComponent(plugin), new CacheComponent(plugin),
                new LockingComponent(plugin), new StorageComponent(plugin), new IoComponent(plugin),
                new MessagingComponent(plugin), new ManagersComponent(plugin), new MetricsComponent(plugin),
                new EconomyComponent(plugin), new CommandsComponent(plugin),
                new ListenersComponent(plugin), new PlaceholderComponent(plugin), new GuiComponent(plugin));
    }

    public void start() {
        if (!active.isEmpty()) return;
        try {
            for (BootstrapComponent component : components) {
                active.add(component); // Include partial startup in rollback.
                component.start();
            }
        } catch (RuntimeException failure) {
            stop();
            throw failure;
        }
    }

    public void stop() {
        for (int i = active.size() - 1; i >= 0; i--) {
            try { active.get(i).stop(); }
            catch (Exception failure) { plugin.getLogger().warning("Error stopping component: " + failure.getMessage()); }
        }
        active.clear();
    }

    public void reload() {
        stop(); // Flush workers before storage closes; do not replace live dependencies piecemeal.
        plugin.reloadConfig();
        start();
    }
}
