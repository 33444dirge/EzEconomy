package com.skyblockexp.ezeconomy.feature;

import com.skyblockexp.ezeconomy.command.PayCommand;
import com.skyblockexp.ezeconomy.core.EzEconomyPlugin;
import com.skyblockexp.ezeconomy.feature.support.TestSupport;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import static org.junit.jupiter.api.Assertions.*;

class PayAllConversionValidationTest {
    @Test void payAllUsesOriginalCurrencyRegardlessOfRecipientPreference() {
        var server = MockBukkit.mock();
        try {
            EzEconomyPlugin plugin = MockBukkit.load(EzEconomyPlugin.class);
            var storage = new TestSupport.MockStorage();
            plugin.setStorage(storage);
            plugin.setLockManager(null);
            plugin.getConfig().set("pay.confirmation.threshold", -1);
            plugin.getConfig().set("pay.pay_all.enabled", true);
            plugin.getConfig().set("pay.pay_all.require_permission", false);
            plugin.getConfig().set("pay.pay_all.include_offline", false);
            var payer = server.addPlayer("Payer");
            var first = server.addPlayer("First");
            var unsupported = server.addPlayer("Unsupported");
            payer.setOp(true);
            String currency = plugin.getDefaultCurrency();
            storage.setBalance(payer.getUniqueId(), currency, 100);
            storage.setBalance(first.getUniqueId(), currency, 0);
            storage.setBalance(unsupported.getUniqueId(), currency, 0);
            plugin.getCurrencyPreferenceManager().setPreferredCurrency(unsupported.getUniqueId(), "missing_conversion");
            new PayCommand(plugin).onCommand(payer, plugin.getCommand("pay"), "pay", new String[]{"*", "10"});
            assertEquals(100, storage.getBalance(payer.getUniqueId(), currency), 0.00001); // op bypasswithdraw
            assertEquals(10, storage.getBalance(first.getUniqueId(), currency), 0.00001);
            assertEquals(10, storage.getBalance(unsupported.getUniqueId(), currency), 0.00001);
            assertEquals(0, storage.getBalance(unsupported.getUniqueId(), "missing_conversion"), 0.00001);
        } finally { MockBukkit.unmock(); }
    }
}
