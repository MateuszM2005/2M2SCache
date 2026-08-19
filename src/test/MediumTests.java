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
     * A high-throughput stress test with a uniform key distribution.
     * This simulates a workload where all keys are accessed with equal probability.
     */
    @Test
    void highThroughputUniformDistribution() throws InterruptedException {
        final Cache2M2S<Integer, String> cache = new Cache2M2S<>(); // Default large size
        final int testDurationSeconds = 15;
        final int keyRange = 1_000_000;
        final AtomicLong totalOps = new AtomicLong(0);
        final AtomicLong hits = new AtomicLong(0);
        final AtomicInteger errors = new AtomicInteger(0);
        final CountDownLatch latch = new CountDownLatch(numThreads);
        final AtomicBoolean isRunning = new AtomicBoolean(true);

        Runnable task = () -> {
            try {
                while (isRunning.get()) {
                    int key = ThreadLocalRandom.current().nextInt(0, keyRange);
                    // 90% gets, 10% puts (read-heavy workload)
                    if (ThreadLocalRandom.current().nextInt(10) == 0) {
                        cache.put(key, "value-" + key);
                    } else {
                        if (cache.get(key) != null) {
                            hits.incrementAndGet();
                        }
                    }
                    totalOps.incrementAndGet();
                }
            } catch (Exception e) {
                e.printStackTrace();
                errors.incrementAndGet();
            } finally {
                latch.countDown();
            }
        };

        System.out.printf("Starting high-throughput test (Uniform Dist) with %d threads for %d seconds...\n", numThreads, testDurationSeconds);
        for (int i = 0; i < numThreads; i++) {
            executor.submit(task);
        }

        Thread.sleep(TimeUnit.SECONDS.toMillis(testDurationSeconds));
        isRunning.set(false);

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Threads did not finish cleanly.");
        assertEquals(0, errors.get(), "Exceptions were thrown during stress test.");

        long finalOps = totalOps.get();
        double throughput = (double) finalOps / testDurationSeconds;
        System.out.printf("Uniform Dist test complete.\n");
        System.out.printf("  - Throughput: %,.2f ops/sec\n", throughput);
        System.out.printf("  - Hit Rate: %.2f%%\n", (double) hits.get() / Math.max(1, totalOps.get() - hits.get()) * 100);
        System.out.printf("  - Final Cache Size: %,d\n", cache.size());
        assertTrue(finalOps > 0, "No operations were performed.");
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
        final double zipfianSkew = 0.99; // Standard skew for Zipfian
        final AtomicLong totalOps = new AtomicLong(0);
        final AtomicLong hits = new AtomicLong(0);
        final AtomicInteger errors = new AtomicInteger(0);
        final CountDownLatch latch = new CountDownLatch(numThreads);
        final AtomicBoolean isRunning = new AtomicBoolean(true);

        // Simple Zipfian generator for demonstration purposes
        // For more accurate tests, a library like Apache Commons Math is recommended
        class SimpleZipfianGenerator {
            private final ThreadLocalRandom random = ThreadLocalRandom.current();
            private final int size;
            private final double skew;
            private final double bottom;

            SimpleZipfianGenerator(int size, double skew) {
                this.size = size;
                this.skew = skew;
                this.bottom = h(size, skew);
            }

            private double h(int n, double s) {
                return 1.0 / Math.pow(n, 1.0 - s);
            }

            public int nextInt() {
                double u = random.nextDouble();
                double r = u * bottom;
                return (int) Math.floor(Math.pow(r, 1.0 / (1.0 - skew)));
            }
        }

        final SimpleZipfianGenerator zipf = new SimpleZipfianGenerator(keyRange, zipfianSkew);

        Runnable task = () -> {
            try {
                while (isRunning.get()) {
                    int key = zipf.nextInt();
                    if (ThreadLocalRandom.current().nextInt(10) == 0) {
                        cache.put(key, "value-" + key);
                    } else {
                        if (cache.get(key) != null) {
                            hits.incrementAndGet();
                        }
                    }
                    totalOps.incrementAndGet();
                }
            } catch (Exception e) {
                e.printStackTrace();
                errors.incrementAndGet();
            } finally {
                latch.countDown();
            }
        };

        System.out.printf("Starting high-throughput test (Zipfian Dist) with %d threads for %d seconds...\n", numThreads, testDurationSeconds);
        for (int i = 0; i < numThreads; i++) {
            executor.submit(task);
        }

        Thread.sleep(TimeUnit.SECONDS.toMillis(testDurationSeconds));
        isRunning.set(false);

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Threads did not finish cleanly.");
        assertEquals(0, errors.get(), "Exceptions were thrown during stress test.");

        long finalOps = totalOps.get();
        double throughput = (double) finalOps / testDurationSeconds;
        System.out.printf("Zipfian Dist test complete.\n");
        System.out.printf("  - Throughput: %,.2f ops/sec\n", throughput);
        System.out.printf("  - Hit Rate: %.2f%%\n", (double) hits.get() / Math.max(1, totalOps.get() - hits.get()) * 100);
        System.out.printf("  - Final Cache Size: %,d\n", cache.size());
        assertTrue(finalOps > 0, "No operations were performed.");
    }
}
