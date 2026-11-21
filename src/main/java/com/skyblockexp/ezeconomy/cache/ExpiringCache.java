package com.skyblockexp.ezeconomy.cache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Bounded access-order cache. Expired snapshots remain usable until maintenance for stale-while-refresh callers. */
public class ExpiringCache<K, V> {
    private final Map<K, Entry<V>> map = new LinkedHashMap<>(16, 0.75f, true);
    private final int maximumSize;
    private final LongSupplier clock;

    public ExpiringCache() { this(16_384, System::currentTimeMillis); }
    ExpiringCache(int maximumSize, LongSupplier clock) {
        if (maximumSize < 1) throw new IllegalArgumentException("maximumSize must be positive");
        this.maximumSize = maximumSize;
        this.clock = clock;
    }

    public static final class Entry<V> {
        public final V value;
        public final long expiresAt;
        public Entry(V value, long expiresAt) { this.value = value; this.expiresAt = expiresAt; }
    }

    public synchronized Entry<V> getEntry(K key) { return map.get(key); }

    public synchronized void put(K key, V value, long ttlMs) {
        long now = clock.getAsLong();
        long ttl = Math.max(0, ttlMs);
        long expiresAt = ttl > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + ttl;
        map.put(key, new Entry<>(value, expiresAt));
        if (map.size() > maximumSize) {
            cleanUp();
            while (map.size() > maximumSize) map.remove(map.keySet().iterator().next());
        }
    }

    public synchronized void remove(K key) { map.remove(key); }
    public synchronized void clear() { map.clear(); }
    public synchronized void cleanUp() {
        long now = clock.getAsLong();
        map.values().removeIf(entry -> entry.expiresAt <= now);
    }
}
