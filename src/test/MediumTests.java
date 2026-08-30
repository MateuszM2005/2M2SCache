package test;

import concurrent.Cache2M2S;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2M2S doc
 * Medium-scale concurrency tests for Cache2M2S.
 * These tests focus on throughput, stability, and correctness under heavy, sustained load
 * with different varieties of key distributions.
 *
 * Counters are accumulated per thread and summed once at the end. Incrementing a shared
 * atomic per operation would contend on a single cache line and measure that contention
 * instead of the cache.
 */
@Timeout(120)
public class MediumTests {

    private ExecutorService executor;
    private final int numThreads = Math.max(8, Runtime.getRuntime().availableProcessors());

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(numThreads);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    /**
     * Totals collected by one worker thread, merged after the run.
     */
    private static final class Counts {
        long ops;
        long gets;
        long hits;
    }

    /**
     * A high-throughput stress test with a uniform key distribution.
     * This simulates a workload where all keys are accessed with equal probability.
     */
    @Test
    void highThroughputUniformDistribution() throws InterruptedException {
        final Cache2M2S<Integer, String> cache = new Cache2M2S<>(); // Default large size
        final int testDurationSeconds = 15;
        final int keyRange = 1_000_000;

        System.out.printf("Starting high-throughput test (Uniform Dist) with %d threads for %d seconds...%n",
                numThreads, testDurationSeconds);
        Counts totals = runLoad(testDurationSeconds, counts -> {
            int key = ThreadLocalRandom.current().nextInt(0, keyRange);
            recordOperation(cache, key, counts);
        });

        report("Uniform Dist", totals, testDurationSeconds, cache);
        assertTrue(totals.ops > 0, "No operations were performed.");
        assertTrue(cache.size() > 1,
                "A uniform workload over " + keyRange + " keys should fill the cache, but it holds "
                        + cache.size() + " entries.");
    }

    /**
     * Tests performance with a Zipfian distribution, which is more representative of
     * real-world workloads where some keys are much "hotter" than others.
     */
    @Test
    void highThroughputZipfianDistribution() throws InterruptedException {
        final Cache2M2S<Integer, String> cache = new Cache2M2S<>(); // Default large size
        final int testDurationSeconds = 15;
        final int keyRange = 1_000_000;
        final ZipfKeys zipf = new ZipfKeys(keyRange, 0.99, 1 << 20);

        assertTrue(zipf.distinctKeys() > 1000,
                "The key generator collapsed onto " + zipf.distinctKeys() + " distinct keys.");

        System.out.printf("Starting high-throughput test (Zipfian Dist) with %d threads for %d seconds...%n",
                numThreads, testDurationSeconds);
        Counts totals = runLoad(testDurationSeconds, counts -> recordOperation(cache, zipf.next(), counts));

        report("Zipfian Dist", totals, testDurationSeconds, cache);
        assertTrue(totals.ops > 0, "No operations were performed.");
        assertTrue(hitRate(totals) > 50.0,
                "A Zipfian workload should hit far more often than it misses, but the hit rate was "
                        + hitRate(totals) + "%.");
    }

    /**
     * Performs one cache operation, 90% reads and 10% writes, updating the thread's counters.
     */
    private static void recordOperation(Cache2M2S<Integer, String> cache, int key, Counts counts) {
        if (ThreadLocalRandom.current().nextInt(10) == 0) {
            cache.put(key, "value-" + key);
        } else {
            counts.gets++;
            if (cache.get(key) != null) {
                counts.hits++;
            }
        }
        counts.ops++;
    }

    /**
     * Runs {@code operation} on every worker thread for the given duration and returns the
     * merged totals. Fails the calling test if any thread threw or did not stop cleanly.
     */
    private Counts runLoad(int durationSeconds, java.util.function.Consumer<Counts> operation)
            throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(numThreads);
        final AtomicBoolean isRunning = new AtomicBoolean(true);
        final AtomicInteger errors = new AtomicInteger(0);
        final AtomicLong ops = new AtomicLong(0);
        final AtomicLong gets = new AtomicLong(0);
        final AtomicLong hits = new AtomicLong(0);

        Runnable task = () -> {
            Counts local = new Counts();
            try {
                while (isRunning.get()) {
                    operation.accept(local);
                }
            } catch (Exception e) {
                e.printStackTrace();
                errors.incrementAndGet();
            } finally {
                ops.addAndGet(local.ops);
                gets.addAndGet(local.gets);
                hits.addAndGet(local.hits);
                latch.countDown();
            }
        };

        for (int i = 0; i < numThreads; i++) {
            executor.submit(task);
        }

        Thread.sleep(TimeUnit.SECONDS.toMillis(durationSeconds));
        isRunning.set(false);

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Threads did not finish cleanly.");
        assertEquals(0, errors.get(), "Exceptions were thrown during stress test.");

        Counts totals = new Counts();
        totals.ops = ops.get();
        totals.gets = gets.get();
        totals.hits = hits.get();
        return totals;
    }

    /**
     * @return hits as a percentage of reads, which is the only denominator that makes the
     *         number comparable between workloads with different read/write mixes
     */
    private static double hitRate(Counts counts) {
        return counts.gets == 0 ? 0.0 : 100.0 * counts.hits / counts.gets;
    }

    private static void report(String label, Counts totals, int durationSeconds, Cache2M2S<?, ?> cache) {
        System.out.printf("%s test complete.%n", label);
        System.out.printf("  - Throughput: %,.2f ops/sec%n", (double) totals.ops / durationSeconds);
        System.out.printf("  - Hit Rate: %.2f%% (%,d hits / %,d reads)%n",
                hitRate(totals), totals.hits, totals.gets);
        System.out.printf("  - Final Cache Size: %,d%n", cache.size());
    }
}
