package com.skyblockexp.ezeconomy.papi.placeholders;

import com.skyblockexp.ezeconomy.papi.testhelpers.TestEzEconomyStubs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.bukkit.OfflinePlayer;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class ComprehensivePAPIExpansionTest {

    @AfterEach
    public void cleanup() throws Exception {
        com.skyblockexp.ezeconomy.papi.EzEconomyPAPIExpansion.TEST_ECONOMY_FOR_TESTS = null;
        // Attempt to clear any global caches if present by resetting CacheManager provider entry
        try {
            Class<?> cm = Class.forName("com.skyblockexp.ezeconomy.cache.CacheManager");
            Field f = cm.getDeclaredField("provider");
            f.setAccessible(true);
            Object provider = f.get(null);
            if (provider != null) {
                try {
                    provider.getClass().getMethod("clear").invoke(provider);
                } catch (NoSuchMethodException ignored) {}
            }
        } catch (ClassNotFoundException ignored) {}
    }

    private OfflinePlayer offlinePlayer(java.util.UUID id) {
        return com.skyblockexp.ezeconomy.papi.testhelpers.TestPlayerFakes.fakeOfflinePlayer(id);
    }

    @Test
    public void unknown_returnsEmpty_and_nullOffline_for_balance() {
        TestEzEconomyStubs.SimpleStorageProvider sp = new TestEzEconomyStubs.SimpleStorageProvider();
        com.skyblockexp.ezeconomy.papi.EzEconomyPAPIExpansion.TEST_ECONOMY_FOR_TESTS = new TestEzEconomyStubs.SimpleTestEz(sp, "dollar");
        com.skyblockexp.ezeconomy.papi.EzEconomyPAPIExpansion expansion = new com.skyblockexp.ezeconomy.papi.EzEconomyPAPIExpansion(null);

        assertEquals("", expansion.onPlaceholderRequest(null, "not_a_placeholder"));
        // balance with null offline player should return "0"
        assertEquals("0", expansion.onPlaceholderRequest(null, "balance"));
    }
}
