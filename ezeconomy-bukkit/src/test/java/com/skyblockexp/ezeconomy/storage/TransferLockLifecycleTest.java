package com.skyblockexp.ezeconomy.storage;

import com.skyblockexp.ezeconomy.lock.LocalLockManager;
import java.lang.ref.Reference;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransferLockLifecycleTest {
    @Test void concurrentCallersKeepTheSameLiveLock() throws Exception {
        UUID account = UUID.randomUUID();
        var lock = TransferLockManager.getLock(account);
        var workers = Executors.newFixedThreadPool(4);
        try {
            for (int i = 0; i < 100; i++) {
                assertSame(lock, workers.submit(() -> TransferLockManager.getLock(account)).get(2, TimeUnit.SECONDS));
            }
        } finally { workers.shutdownNow(); }
    }

    @Test void collectedLockKeysAreRemovedWithoutDiscardingAReplacement() throws Exception {
        UUID account = UUID.randomUUID();
        var original = TransferLockManager.getLock(account);
        var field = TransferLockManager.class.getDeclaredField("LOCKS");
        field.setAccessible(true);
        Map<?, ?> locks = (Map<?, ?>) field.get(null);
        Reference<?> reference = (Reference<?>) locks.get(account);
        reference.clear(); // deterministically model GC, without a flaky System.gc assertion
        var replacement = TransferLockManager.getLock(account);
        assertNotSame(original, replacement);
        reference.enqueue();
        TransferLockManager.cleanUp();
        assertSame(replacement, TransferLockManager.getLock(account));
        Reference<?> current = (Reference<?>) locks.get(account);
        current.clear();
        current.enqueue();
        TransferLockManager.cleanUp();
        assertFalse(locks.containsKey(account));
    }

    @Test void invalidReleaseCannotConsumeAnotherAccountsToken() throws Exception {
        LocalLockManager manager = new LocalLockManager();
        UUID account = UUID.randomUUID();
        String token = manager.acquire(account, 1000, 0, 1);
        assertNotNull(token);
        assertFalse(manager.release(UUID.randomUUID(), token));
        assertTrue(manager.release(account, token));
        assertFalse(manager.release(account, token));
    }
}
