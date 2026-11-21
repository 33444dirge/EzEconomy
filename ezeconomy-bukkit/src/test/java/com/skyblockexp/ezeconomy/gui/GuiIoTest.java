package com.skyblockexp.ezeconomy.gui;

import com.skyblockexp.ezeconomy.api.storage.StorageProvider;
import com.skyblockexp.ezeconomy.core.EzEconomyPlugin;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GuiIoTest {
    @Test void balanceMenuDefersDatabaseReadsToOwnedIoExecutor() {
        EzEconomyPlugin plugin = mock(EzEconomyPlugin.class);
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(uuid);
        var config = new YamlConfiguration();
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDefaultCurrency()).thenReturn("j");
        StorageProvider storage = mock(StorageProvider.class);
        when(plugin.getStorageOrWarn()).thenReturn(storage);
        when(storage.getBanks()).thenReturn(Set.of());
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        when(plugin.submitIo(any())).thenAnswer(call -> { submitted.set(call.getArgument(0)); return true; });
        new BalanceAction().open(plugin, player);
        verify(storage, never()).getBalance(any(), any());
        assertNotNull(submitted.get());
        try (var platform = mockStatic(com.skyblockexp.ezeconomy.util.scheduler.PlatformScheduler.class)) {
            submitted.get().run();
            verify(storage).getBalance(uuid, "j");
            platform.verify(() -> com.skyblockexp.ezeconomy.util.scheduler.PlatformScheduler.runForPlayer(
                    eq(plugin), eq(player), any(), any(), eq(1L)));
        }
    }

    @Test void historyMenuDefersTransactionsUntilWorkerAndReturnsToPlayerScheduler() {
        EzEconomyPlugin plugin = mock(EzEconomyPlugin.class);
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(uuid);
        when(plugin.getDefaultCurrency()).thenReturn("j");
        when(plugin.getTransactions(uuid, "j")).thenReturn(List.of());
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        when(plugin.submitIo(any())).thenAnswer(call -> { submitted.set(call.getArgument(0)); return true; });
        new HistoryAction().open(plugin, player);
        verify(plugin, never()).getTransactions(any(), any());
        try (var platform = mockStatic(com.skyblockexp.ezeconomy.util.scheduler.PlatformScheduler.class)) {
            submitted.get().run();
            verify(plugin).getTransactions(uuid, "j");
            platform.verify(() -> com.skyblockexp.ezeconomy.util.scheduler.PlatformScheduler.runForPlayer(
                    eq(plugin), eq(player), any(), any(), eq(1L)));
        }
    }
}
