package com.skyblockexp.ezeconomy.storage;

import com.skyblockexp.ezeconomy.core.EzEconomyPlugin;
import com.skyblockexp.ezeconomy.storage.sqlite.SQLiteBackgroundPersistenceService;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SQLiteBackgroundDrainTest {
    @TempDir Path directory;

    @Test void cachedPendingDepositCannotBeCountedTwiceToAuthorizeAnOverdraft() throws Exception {
        EzEconomyPlugin plugin = mock(EzEconomyPlugin.class);
        var config = new org.bukkit.configuration.file.YamlConfiguration();
        config.set("performance.balance-cache.enabled", true);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        var database = new org.bukkit.configuration.file.YamlConfiguration();
        database.set("sqlite.file", "overdraft.db");
        database.set("sqlite.background-flush-ms", 10000);
        var storage = new SQLiteStorageProvider(plugin, database);
        UUID account = UUID.randomUUID();
        try {
            assertTrue(storage.depositAndGetBalance(account, "j", 100).isSuccess());
            assertEquals(110, storage.depositAndGetBalance(account, "j", 10).getBalance(), 0.00001);
            assertFalse(storage.withdrawAndGetBalance(account, "j", 150).isSuccess());
            assertTrue(storage.withdrawAndGetBalance(account, "j", 80).isSuccess());
            assertFalse(storage.withdrawAndGetBalance(account, "j", 31).isSuccess());
            assertEquals(30, storage.getBalance(account, "j"), 0.00001);
        } finally {
            storage.shutdown();
            com.skyblockexp.ezeconomy.api.spending.IslandSpendLedger.close();
            com.skyblockexp.ezeconomy.cache.CacheManager.close();
        }
    }

    @Test void aFullQueueAndSmallBatchDoNotStrandAcknowledgedDeposits() throws Exception {
        Path database = directory.resolve("drain.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            connection.createStatement().execute("CREATE TABLE balances(id TEXT PRIMARY KEY, uuid TEXT, currency TEXT, balance DOUBLE)");
        }
        EzEconomyPlugin plugin = mock(EzEconomyPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        var writer = new SQLiteBackgroundPersistenceService(plugin, database.toString(), "balances", 16, 1, 10);
        try {
            for (int i = 0; i < 100; i++) {
                String uuid = UUID.randomUUID().toString();
                writer.submitBalanceDelta(uuid + ":j", uuid, "j", 1.0);
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            long total = 0;
            while (System.nanoTime() < deadline) {
                try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                     var statement = connection.createStatement();
                     var result = statement.executeQuery("SELECT COALESCE(SUM(balance), 0) FROM balances")) {
                    result.next(); total = result.getLong(1);
                }
                if (total == 100) break;
                Thread.sleep(20);
            }
            assertEquals(100, total, "Every accepted delta must reach storage while the worker is running");
        } finally { writer.shutdown(); }
    }
}
