package com.skyblockexp.ezeconomy.util;

import com.skyblockexp.ezeconomy.api.events.PreTransactionEvent;
import com.skyblockexp.ezeconomy.api.events.TransactionType;
import com.skyblockexp.ezeconomy.util.scheduler.PlatformScheduler;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FoliaEventDispatchTest {
    private PreTransactionEvent event() {
        return new PreTransactionEvent(UUID.randomUUID(), UUID.randomUUID(), BigDecimal.ONE, TransactionType.TRANSFER);
    }
    @Test void foliaTickDispatchesDirectlyWithoutWaitingForAnotherTick() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        PluginManager manager = mock(PluginManager.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(manager);
        try (var platform = mockStatic(PlatformScheduler.class)) {
            platform.when(PlatformScheduler::isFolia).thenReturn(true);
            platform.when(PlatformScheduler::isTickThread).thenReturn(true);
            var event = event();
            assertTrue(EventDispatcher.fireSyncAndAllow(plugin, event));
            verify(manager).callEvent(event);
            platform.verify(PlatformScheduler::isFolia);
            platform.verify(PlatformScheduler::isTickThread);
            platform.verifyNoMoreInteractions();
        }
    }
    @Test void missingCancellationListenerFailsClosed() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getServer()).thenReturn(mock(Server.class));
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        try (var platform = mockStatic(PlatformScheduler.class)) {
            platform.when(() -> PlatformScheduler.callSync(eq(plugin), any())).thenThrow(new ExecutionException(new IllegalStateException("scheduler stopped")));
            assertFalse(EventDispatcher.fireSyncAndAllow(plugin, event()));
        }
    }
}
