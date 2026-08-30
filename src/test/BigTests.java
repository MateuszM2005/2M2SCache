package test;

import concurrent.Cache2M2S;
import org.junit.jupiter.api.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2M2S doc
 * Large-scale stress tests for Cache2M2S.
 * These tests are resource-intensive and designed to run for an extended period
 * to check for stability, memory leaks, and performance under extreme load.
 * They are disabled by default.
 */
@Disabled("BigTests are disabled by default due to their resource-intensive nature.")
@Timeout(value = 15, unit = TimeUnit.MINUTES)
public class BigTests {

    private ExecutorService executor;
    private final int numThreads = Math.max(8, Runtime.getRuntime().availableProcessors() * 2);

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(numThreads);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    /**
     * A massive stress test involving 10M+ keys and 100M+ operations.
     * This test runs for a long duration to check for potential memory leaks,
     * deadlocks, or other long-term stability issues.
     */
    @Test
    void massiveStressTest() throws InterruptedException {
        final int capacity = 10_000_000;
        final long totalOpsTarget = 100_000_000;
        final int keyRange = 15_000_000;
        final Cache2M2S<Integer, String> cache = new Cache2M2S<>(capacity);
        final AtomicLong totalOps = new AtomicLong(0);
        final AtomicInteger errors = new AtomicInteger(0);
        final CountDownLatch latch = new CountDownLatch(numThreads);

        System.out.printf("Starting massive stress test with %d threads (Target: %,d ops, Capacity: %,d)...\n",
                numThreads, totalOpsTarget, capacity);

        long startTime = System.currentTimeMillis();

        Runnable task = () -> {
            try {
                while (totalOps.getAndIncrement() < totalOpsTarget) {
                    int key = ThreadLocalRandom.current().nextInt(0, keyRange);
                    // 80% gets, 20% puts
                    if (ThreadLocalRandom.current().nextInt(5) == 0) {
                        cache.put(key, "value-" + key);
                    } else {
                        cache.get(key);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                errors.incrementAndGet();
            } finally {
                latch.countDown();
            }
        };

        for (int i = 0; i < numThreads; i++) {
            executor.submit(task);
        }

        assertTrue(latch.await(10, TimeUnit.MINUTES), "Test timed out, indicating a potential deadlock or severe performance issue.");
        assertEquals(0, errors.get(), "Exceptions were thrown during massive stress test.");

        long endTime = System.currentTimeMillis();
        double durationSeconds = (endTime - startTime) / 1000.0;
        double throughput = totalOpsTarget / durationSeconds;

        System.out.println("Massive stress test complete.");
        System.out.printf("  - Duration: %.2f seconds\n", durationSeconds);
        System.out.printf("  - Throughput: %,.2f ops/sec\n", throughput);
        System.out.printf("  - Final Cache Size: %,d\n", cache.size());
        // The buffer holds up to 4096 entries that are in the map but not yet subject to eviction.
        int maxSize = capacity + 4096 + numThreads;
        assertTrue(cache.size() <= maxSize,
                "Cache size (" + cache.size() + ") exceeded its maximum (" + maxSize + ").");
    }
}
