package com.skyblockexp.ezeconomy.service;

import com.skyblockexp.ezeconomy.core.EzEconomyPlugin;
import com.skyblockexp.ezeconomy.feature.support.TestSupport;
import com.skyblockexp.ezeconomy.lock.LockManager;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentCurrencyRetentionTest {
    @Test void transfersIgnoreRecipientPreferenceAndLegacyExchangeRates() throws Exception { check(false, 100, 10); }
    @Test void distributedTransfersStillLockAndReleaseInTheOriginalCurrency() throws Exception { check(true, 100, 10); }
    @Test void insufficientFundsRemainUnchangedWithDifferentRecipientPreference() throws Exception { check(true, 3, 0); }

    private void check(boolean distributed, double initial, double paid) throws Exception {
        var server = MockBukkit.mock();
        try {
            EzEconomyPlugin plugin = MockBukkit.load(EzEconomyPlugin.class);
            var from = server.addPlayer("Payer");
            var to = server.addPlayer("Recipient");
            var storage = new TestSupport.MockStorage();
            plugin.setStorage(storage);
            plugin.getConfig().set("multi-currency.enabled", true);
            plugin.getConfig().set("multi-currency.conversion.dollar.euro", 99);
            plugin.getCurrencyPreferenceManager().setPreferredCurrency(to.getUniqueId(), "euro");
            storage.setBalance(from.getUniqueId(), "dollar", initial);
            LockManager locks = null;
            if (distributed) {
                locks = mock(LockManager.class, CALLS_REAL_METHODS);
                when(locks.acquire(any(), anyLong(), anyLong(), anyInt())).thenReturn("token");
                when(locks.release(any(), any())).thenReturn(true);
            }
            plugin.setLockManager(locks);
            assertTrue(PaymentExecutor.execute(plugin, from, "Recipient", BigDecimal.TEN, "dollar"));
            assertEquals(initial - paid, storage.getBalance(from.getUniqueId(), "dollar"), 0.00001);
            assertEquals(paid, storage.getBalance(to.getUniqueId(), "dollar"), 0.00001);
            assertEquals(0, storage.getBalance(to.getUniqueId(), "euro"), 0.00001);
            if (locks != null) {
                verify(locks, times(2)).acquire(any(), anyLong(), anyLong(), anyInt());
                verify(locks, times(2)).release(any(), eq("token"));
            }
        } finally { MockBukkit.unmock(); }
    }
}
