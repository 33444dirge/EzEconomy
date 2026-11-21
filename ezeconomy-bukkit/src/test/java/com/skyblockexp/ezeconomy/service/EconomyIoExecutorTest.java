package com.skyblockexp.ezeconomy.service;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EconomyIoExecutorTest {
    @Test void fullQueueRejectsInsteadOfRunningOnCallerAndShutdownDrainsAcceptedWork() throws Exception {
        var executor = new EconomyIoExecutor(Logger.getAnonymousLogger(), 1, 1);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var completed = new AtomicInteger();
        try {
            assertTrue(executor.submit(() -> {
                started.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                completed.incrementAndGet();
            }));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(executor.submit(completed::incrementAndGet));
            assertFalse(executor.submit(() -> fail("Rejected IO must not run on the caller")));
        } finally {
            release.countDown();
            executor.close();
        }
        assertEquals(2, completed.get());
        assertFalse(executor.submit(() -> fail("Closed executor must reject work")));
    }
}
