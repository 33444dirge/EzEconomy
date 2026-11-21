package com.skyblockexp.ezeconomy.cache;

/**
 * Generic cache provider API used by the project.
 */
public interface CacheProvider<K, V> extends AutoCloseable {
    default void cleanUp() {}
    @Override default void close() {}
    ExpiringCache.Entry<V> getEntry(K key);
    void put(K key, V value, long ttlMs);
    void remove(K key);
}
