package com.skyblockexp.ezeconomy.feature;

import com.skyblockexp.ezeconomy.api.storage.StorageProvider;
import com.skyblockexp.ezeconomy.command.CurrencyCommand;
import com.skyblockexp.ezeconomy.command.EzEconomyCommand;
import com.skyblockexp.ezeconomy.core.EzEconomyPlugin;
import com.skyblockexp.ezeconomy.core.VaultEconomyImpl;
import com.skyblockexp.ezeconomy.feature.support.TestSupport;
import com.skyblockexp.ezeconomy.storage.SQLiteStorageProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.logging.Logger;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RemovedFeaturesTest {
    @TempDir Path directory;

    @Test void shippedConfigurationAndTranslationsRemainValidAndContainNoRemovedCommands() throws Exception {
        for (String name : java.util.List.of("config.yml", "plugin.yml", "config-sqlite.yml", "config-mysql.yml",
                "config-mongodb.yml", "messages.yml", "languages/en.yml", "languages/zh.yml",
                "languages/nl.yml", "languages/fr.yml", "languages/es.yml")) {
            try (var resource = EzEconomyPlugin.class.getClassLoader().getResourceAsStream(name)) {
                assertNotNull(resource, name);
                var yaml = new YamlConfiguration();
                yaml.load(new java.io.InputStreamReader(resource, java.nio.charset.StandardCharsets.UTF_8));
                assertFalse(yaml.contains("commands.bank"), name);
                assertFalse(yaml.contains("daily-reward"), name);
                assertFalse(yaml.contains("banking"), name);
                assertFalse(yaml.contains("multi-currency.conversion"), name);
            }
        }
    }

    @Test void vaultOnlyReportsUnsupportedBanksWithoutTouchingStorage() {
        EzEconomyPlugin plugin = mock(EzEconomyPlugin.class);
        var config = new YamlConfiguration();
        config.set("banking.enabled", true); // Old config must not re-enable removed features.
        when(plugin.getConfig()).thenReturn(config);
        StorageProvider storage = mock(StorageProvider.class);
        when(plugin.getStorage()).thenReturn(storage);
        var vault = new VaultEconomyImpl(plugin);
        assertFalse(vault.hasBankSupport());
        assertTrue(vault.getBanks().isEmpty());
        var player = mock(org.bukkit.OfflinePlayer.class);
        for (var result : java.util.List.of(vault.createBank("bank", "player"), vault.createBank("bank", player),
                vault.deleteBank("bank"), vault.bankBalance("bank"), vault.bankHas("bank", 1),
                vault.bankDeposit("bank", 1), vault.bankWithdraw("bank", 1),
                vault.isBankOwner("bank", "player"), vault.isBankOwner("bank", player),
                vault.isBankMember("bank", "player"), vault.isBankMember("bank", player))) {
            assertEquals(EconomyResponse.ResponseType.NOT_IMPLEMENTED, result.type);
        }
        verifyNoInteractions(storage);
    }

    @Test void sqliteDoesNotCreateBankTablesAndPreservesOldBankData() throws Exception {
        Path database = directory.resolve("economy.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            connection.createStatement().execute("CREATE TABLE legacy_banks(marker TEXT)");
            connection.createStatement().execute("INSERT INTO legacy_banks VALUES ('preserved')");
        }
        EzEconomyPlugin plugin = mock(EzEconomyPlugin.class);
        var config = new YamlConfiguration();
        config.set("performance.balance-cache.enabled", false);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        var dbConfig = new YamlConfiguration();
        dbConfig.set("sqlite.file", "economy.db");
        var storage = new SQLiteStorageProvider(plugin, dbConfig);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('banks','bank_members')")) {
                result.next(); assertEquals(0, result.getInt(1));
            }
            try (var result = statement.executeQuery("SELECT marker FROM legacy_banks")) {
                assertTrue(result.next()); assertEquals("preserved", result.getString(1));
            }
        } finally {
            storage.shutdown();
            com.skyblockexp.ezeconomy.service.spending.SqlSpendingLedger.close();
        }
    }

    @Test void removedCommandsDoNotMutateMoneyOrCreateRewardFiles() {
        var server = MockBukkit.mock();
        try {
            EzEconomyPlugin plugin = MockBukkit.load(EzEconomyPlugin.class);
            assertNull(plugin.getCommand("bank"));
            assertFalse(plugin.getConfig().contains("daily-reward"));
            assertFalse(plugin.getConfig().contains("banking"));
            assertFalse(plugin.getConfig().contains("multi-currency.conversion"));
            plugin.getConfig().set("daily-reward.enabled", true);
            plugin.getConfig().set("daily-reward.amount", 1000);
            plugin.getConfig().set("multi-currency.enabled", true);
            plugin.getConfig().set("multi-currency.conversion.dollar.euro", 100);
            var player = server.addPlayer("WalletOwner");
            player.setOp(true);
            var storage = new TestSupport.MockStorage();
            plugin.setStorage(storage);
            UUID uuid = player.getUniqueId();
            storage.setBalance(uuid, "dollar", 100);
            new CurrencyCommand(plugin).onCommand(player, plugin.getCommand("currency"), "currency",
                    new String[]{"convert", "dollar", "euro", "10"});
            new EzEconomyCommand(plugin).onCommand(player, plugin.getCommand("ezeconomy"), "ezeconomy",
                    new String[]{"daily", "reset", "WalletOwner"});
            assertEquals(100, storage.getBalance(uuid, "dollar"), 0.00001);
            assertEquals(0, storage.getBalance(uuid, "euro"), 0.00001);
            assertFalse(Files.exists(plugin.getDataFolder().toPath().resolve("daily-rewards.yml")));
        } finally { MockBukkit.unmock(); }
    }
}
