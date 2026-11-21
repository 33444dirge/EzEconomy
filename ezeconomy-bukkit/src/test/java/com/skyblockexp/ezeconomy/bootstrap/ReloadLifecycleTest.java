package com.skyblockexp.ezeconomy.bootstrap;

import com.skyblockexp.ezeconomy.command.BalanceCommand;
import com.skyblockexp.ezeconomy.core.EzEconomyPlugin;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import net.milkbowl.vault.economy.Economy;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import static org.junit.jupiter.api.Assertions.*;

class ReloadLifecycleTest {
    @Test void repeatedReloadClosesReplacedWorkersAndKeepsOneVaultRegistration() throws Exception {
        var server = MockBukkit.mock();
        try {
            EzEconomyPlugin plugin = MockBukkit.load(EzEconomyPlugin.class);
            for (int i = 0; i < 3; i++) {
                var oldCommand = (BalanceCommand) plugin.getCommand("balance").getExecutor();
                var oldVault = plugin.getVaultEconomy();
                var oldFlows = plugin.getPayFlowManager();
                oldFlows.startAwaiting(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "recipient");
                plugin.reloadRuntime();
                var readerField = BalanceCommand.class.getDeclaredField("readers");
                readerField.setAccessible(true);
                assertTrue(((ThreadPoolExecutor) readerField.get(oldCommand)).isShutdown());
                var flowsField = oldFlows.getClass().getDeclaredField("flows");
                flowsField.setAccessible(true);
                assertTrue(((Map<?, ?>) flowsField.get(oldFlows)).isEmpty());
                assertNotSame(oldCommand, plugin.getCommand("balance").getExecutor());
                assertNotSame(oldVault, plugin.getVaultEconomy());
                assertEquals(1, server.getServicesManager().getRegistrations(Economy.class).size());
                assertSame(plugin.getVaultEconomy(), server.getServicesManager().load(Economy.class));
            }
        } finally { MockBukkit.unmock(); }
    }
}
