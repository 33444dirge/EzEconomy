package com.skyblockexp.ezeconomy.bootstrap.component;

import com.skyblockexp.ezeconomy.bootstrap.BootstrapComponent;
import com.skyblockexp.ezeconomy.core.EzEconomyPlugin;
import java.io.File;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

public class GuiComponent implements BootstrapComponent {
    private final EzEconomyPlugin plugin;

    public GuiComponent(EzEconomyPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void start() {
        File file = new File(plugin.getDataFolder(), "user-gui.yml");
        if (!file.exists()) {
            try (java.io.InputStream resource = plugin.getResource("user-gui.yml")) {
                if (resource == null) throw new IllegalStateException("Missing user-gui.yml resource");
                plugin.saveResource("user-gui.yml", false);
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("Cannot load user-gui.yml", failure);
            }
        }
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        plugin.setUserGuiConfig(cfg);
    }

    @Override
    public void stop() {
        // no-op
    }

    @Override
    public void reload() {
        start();
    }
}
