package com.skyblockexp.ezeconomy.cache;

/** Owns the selected cache provider and its network/database resources. */
public final class CacheManager {
    private static volatile CacheProvider<?, ?> provider = new LocalCacheProvider<>();
    private CacheManager() {}

    @SuppressWarnings("unchecked")
    public static <K, V> CacheProvider<K, V> getProvider() { return (CacheProvider<K, V>) provider; }
    public static String getProviderClassName() { return provider.getClass().getName(); }

    public static synchronized void setStrategy(CachingStrategy strategy) {
        CacheProvider<?, ?> next;
        try {
            String type = switch (strategy) {
                case REDIS -> "com.skyblockexp.ezeconomy.redis.cache.RedisCacheProvider";
                case BUNGEECORD -> "com.skyblockexp.ezeconomy.bungeecord.cache.BungeeCacheProvider";
                case DATABASE -> "com.skyblockexp.ezeconomy.storage.cache.DatabaseCacheProvider";
                default -> null;
            };
            next = type == null ? new LocalCacheProvider<>()
                    : (CacheProvider<?, ?>) Class.forName(type).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError failure) {
            next = new LocalCacheProvider<>();
        }
        replace(next);
    }

    public static void cleanUp() { provider.cleanUp(); }
    public static synchronized void close() { replace(new LocalCacheProvider<>()); }
    private static void replace(CacheProvider<?, ?> next) {
        CacheProvider<?, ?> old = provider;
        provider = next;
        try { old.close(); } catch (RuntimeException ignored) { /* Release the remaining lifecycle components. */ }
    }
}
