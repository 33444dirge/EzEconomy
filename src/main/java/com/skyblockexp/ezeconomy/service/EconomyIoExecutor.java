package com.skyblockexp.ezeconomy.service;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/** Plugin-owned bounded workers. Rejected work never runs on a region tick. */
public final class EconomyIoExecutor implements AutoCloseable {
    private final ThreadPoolExecutor workers;
    private final Logger logger;
    public EconomyIoExecutor(Logger logger, int threads, int capacity) {
        this.logger = logger;
        int count = Math.max(1, Math.min(16, threads));
        workers = new ThreadPoolExecutor(count, count, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(1, Math.min(16_384, capacity))), runnable -> {
                    Thread thread = new Thread(runnable, "EzEconomy-IO");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        workers.allowCoreThreadTimeOut(true);
    }
    public boolean submit(Runnable task) {
        try {
            workers.execute(() -> {
                try { task.run(); }
                catch (RuntimeException failure) { logger.log(java.util.logging.Level.WARNING, "Economy IO task failed", failure); }
            });
            return true;
        } catch (RejectedExecutionException full) {
            logger.warning("Economy IO queue is full or stopping; request rejected.");
            return false;
        }
    }
    @Override public void close() {
        workers.shutdown();
        try {
            if (!workers.awaitTermination(15L, TimeUnit.SECONDS)) {
                int queued = workers.shutdownNow().size();
                logger.warning("Economy IO shutdown timed out; cancelled queued tasks: " + queued);
            }
        } catch (InterruptedException interrupted) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
