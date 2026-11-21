package com.skyblockexp.ezeconomy.storage;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Keeps lock identity while callers hold it, without retaining every historical account. */
public final class TransferLockManager {
    private static final ConcurrentHashMap<UUID, LockReference> LOCKS = new ConcurrentHashMap<>();
    private static final ReferenceQueue<ReentrantLock> COLLECTED = new ReferenceQueue<>();

    private TransferLockManager() {}

    public static ReentrantLock getLock(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        cleanUp();
        for (;;) {
            LockReference existing = LOCKS.get(uuid);
            ReentrantLock lock = existing == null ? null : existing.get();
            if (lock != null) return lock;
            ReentrantLock created = new ReentrantLock();
            LockReference next = new LockReference(uuid, created);
            if (existing == null ? LOCKS.putIfAbsent(uuid, next) == null : LOCKS.replace(uuid, existing, next)) {
                return created;
            }
        }
    }

    public static void cleanUp() {
        LockReference reference;
        while ((reference = (LockReference) COLLECTED.poll()) != null) {
            LOCKS.remove(reference.uuid, reference);
        }
    }

    private static final class LockReference extends WeakReference<ReentrantLock> {
        private final UUID uuid;
        LockReference(UUID uuid, ReentrantLock lock) {
            super(lock, COLLECTED);
            this.uuid = uuid;
        }
    }
}
