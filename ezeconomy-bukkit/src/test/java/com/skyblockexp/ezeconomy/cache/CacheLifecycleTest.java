package com.skyblockexp.ezeconomy.cache;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CacheLifecycleTest {
    @Test void maintenanceReclaimsExpiredEntriesButPreservesFreshSnapshots() {
        AtomicLong clock = new AtomicLong(100);
        ExpiringCache<String, String> cache = new ExpiringCache<>(2, clock::get);
        cache.put("expired", "old", 10);
        cache.put("fresh", "new", 100);
        clock.set(111);
        assertEquals("old", cache.getEntry("expired").value); // stale-while-refresh remains supported
        cache.cleanUp();
        assertNull(cache.getEntry("expired"));
        assertEquals("new", cache.getEntry("fresh").value);
    }

    @Test void capacityEvictsLeastRecentlyUsedAndCloseDropsReferences() {
        ExpiringCache<String, String> cache = new ExpiringCache<>(2, () -> 0L);
        cache.put("a", "a", 100);
        cache.put("b", "b", 100);
        cache.getEntry("a");
        cache.put("c", "c", 100);
        assertNull(cache.getEntry("b"));
        assertNotNull(cache.getEntry("a"));
        assertNotNull(cache.getEntry("c"));
        LocalCacheProvider<String, String> provider = new LocalCacheProvider<>();
        provider.put("player", "profile", 10000);
        provider.close();
        assertNull(provider.getEntry("player"));
    }

    @Test void switchingStrategyClosesThePreviousProvider() throws Exception {
        var field = CacheManager.class.getDeclaredField("provider");
        field.setAccessible(true);
        var provider = new LocalCacheProvider<String, String>();
        provider.put("old", "reference", 10000);
        field.set(null, provider);
        CacheManager.setStrategy(CachingStrategy.LOCAL);
        assertNull(provider.getEntry("old"));
        assertNotSame(provider, CacheManager.getProvider());
        CacheManager.close();
    }
}
