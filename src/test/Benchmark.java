package test;

import concurrent.Cache2M2S;
import org.junit.jupiter.api.*;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 2M2S doc
 * Performance benchmarks comparing Cache2M2S to other cache implementations.
 * These tests provide insights into throughput and hit-rate performance.
 * They are disabled by default and should be run manually for analysis.
 *
 * Throughput is measured across a range of thread counts. A single-threaded number says
 * nothing about a design whose whole purpose is to amortize maintenance across threads;
 * the interesting question is the shape of the curve.
 *
 * Worker threads count operations locally and publish once at the end. Sharing a counter
 * per operation would make the benchmark measure that counter.
 */
@Disabled("Benchmarks are disabled by default and should be run manually.")
@Timeout(value = 5, unit = TimeUnit.MINUTES)
public class Benchmark {

    private static final int THROUGHPUT_SECONDS = 3;
    private static final int HIT_RATE_SECONDS = 10;

    @Test
    void compareThroughputWithConcurrentHashMap() throws InterruptedException {
        final int keyRange = 1_000_000;
        final int maxThreads = Math.max(1, Runtime.getRuntime().availableProcessors());

        System.out.println("--- Throughput scaling (50% get / 50% put, uniform keys) ---");
        System.out.printf("%-8s %20s %20s %8s%n", "threads", "Cache2M2S ops/s", "CHM ops/s", "ratio");

        for (int threads = 1; threads <= maxThreads; threads *= 2) {
            final Cache2M2S<Integer, String> cache = new Cache2M2S<>(keyRange);
            double cacheOps = measureThroughput(threads, () -> {
                int key = ThreadLocalRandom.current().nextInt(keyRange);
                if (ThreadLocalRandom.current().nextBoolean()) {
                    cache.put(key, "value-" + key);
                } else {
                    cache.get(key);
                }
            });

            final ConcurrentHashMap<Integer, String> chm = new ConcurrentHashMap<>(keyRange);
            double chmOps = measureThroughput(threads, () -> {
                int key = ThreadLocalRandom.current().nextInt(keyRange);
                if (ThreadLocalRandom.current().nextBoolean()) {
                    chm.put(key, "value-" + key);
                } else {
                    chm.get(key);
                }
            });

            System.out.printf("%-8d %,20.0f %,20.0f %8.2f%n", threads, cacheOps, chmOps, cacheOps / chmOps);
        }
    }

    @Test
    void compareHitRateWithSimpleLruCache() throws InterruptedException {
        final int capacity = 100_000;
        final int keyRange = 200_000; // Key range larger than capacity to force evictions
        final ZipfKeys zipf = new ZipfKeys(keyRange, 0.99, 1 << 20);

        System.out.println("--- Hit rate, read-through, Zipfian keys, single thread ---");
        System.out.printf("keys %,d over capacity %,d, %,d distinct keys drawn%n",
                keyRange, capacity, zipf.distinctKeys());

        Cache2M2S<Integer, String> cache = new Cache2M2S<>(capacity);
        long[] cacheStats = measureHitRate(zipf, cache::get, cache::put);
        report("Cache2M2S", cacheStats);

        Map<Integer, String> lru = Collections.synchronizedMap(
                new LinkedHashMap<Integer, String>(capacity, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<Integer, String> eldest) {
                        return size() > capacity;
                    }
                });
        long[] lruStats = measureHitRate(zipf, lru::get, lru::put);
        report("LinkedHashMap LRU", lruStats);
    }

    private static void report(String label, long[] stats) {
        long hits = stats[0];
        long misses = stats[1];
        System.out.printf("%-20s hit rate %6.2f%%  (hits %,d / reads %,d)%n",
                label, 100.0 * hits / (hits + misses), hits, hits + misses);
    }

    // --- Helper Methods ---

    /**
     * Runs {@code operation} on {@code threads} workers for a fixed duration.
     *
     * @return operations per second across all threads
     */
    private static double measureThroughput(int threads, Runnable operation) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong totalOps = new AtomicLong(0);
        CountDownLatch latch = new CountDownLatch(threads);

        try {
            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    long ops = 0;
                    try {
                        while (running.get()) {
                            operation.run();
                            ops++;
                        }
                    } finally {
                        totalOps.addAndGet(ops);
                        latch.countDown();
                    }
                });
            }

            long start = System.nanoTime();
            Thread.sleep(TimeUnit.SECONDS.toMillis(THROUGHPUT_SECONDS));
            running.set(false);
            latch.await(30, TimeUnit.SECONDS);
            double elapsedSeconds = (System.nanoTime() - start) / 1e9;
            return totalOps.get() / elapsedSeconds;
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Drives a read-through workload: a miss is followed by an insert, which is how a cache is
     * actually used and the only way its capacity gets exercised.
     *
     * @return {@code {hits, misses}}
     */
    private static long[] measureHitRate(ZipfKeys zipf,
                                         Function<Integer, String> get,
                                         BiConsumer<Integer, String> put) {
        long hits = 0;
        long misses = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(HIT_RATE_SECONDS);

        while (System.nanoTime() < deadline) {
            // Check the clock once per block of operations; System.nanoTime costs about as much
            // as the cache operation being measured.
            for (int i = 0; i < 1024; i++) {
                int key = zipf.next();
                if (get.apply(key) != null) {
                    hits++;
                } else {
                    misses++;
                    put.accept(key, "value-" + key);
                }
            }
        }
        return new long[]{hits, misses};
    }
}
