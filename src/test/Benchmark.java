package test;

import concurrent.Cache2M2S;
import org.junit.jupiter.api.*;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 2M2S doc
 * Performance benchmarks comparing Cache2M2S to other cache implementations.
 * These tests provide insights into throughput and hit-rate performance.
 * They are disabled by default and should be run manually for analysis.
 */
@Disabled("Benchmarks are disabled by default and should be run manually.")
@Timeout(value = 5, unit = TimeUnit.MINUTES)
public class Benchmark {

    private ExecutorService executor;
    //private final int numThreads = Math.max(8, Runtime.getRuntime().availableProcessors());
    private final int numThreads = 1;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(numThreads);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void compareThroughputWithConcurrentHashMap() throws InterruptedException {
        System.out.println("--- Throughput Benchmark ---");
        int testDurationSeconds = 10;
        int keyRange = 1_000_000;

        // Benchmark Cache2M2S
        System.out.println("Benchmarking Cache2M2S...");
        Cache2M2S<Integer, String> cache2m2s = new Cache2M2S<>(keyRange);
        long cache2m2sOps = runBenchmark(keyRange, testDurationSeconds, (key, value) -> {
            if (ThreadLocalRandom.current().nextBoolean()) {
                cache2m2s.put(key, value);
            } else {
                cache2m2s.get(key);
            }
        });
        System.out.printf("Cache2M2S Throughput: %,.2f ops/sec\n\n", (double) cache2m2sOps / testDurationSeconds);

        // Benchmark ConcurrentHashMap
        System.out.println("Benchmarking ConcurrentHashMap...");
        ConcurrentHashMap<Integer, String> chm = new ConcurrentHashMap<>(keyRange);
        long chmOps = runBenchmark(keyRange, testDurationSeconds, (key, value) -> {
            if (ThreadLocalRandom.current().nextBoolean()) {
                chm.put(key, value);
            } else {
                chm.get(key);
            }
        });
        System.out.printf("ConcurrentHashMap Throughput: %,.2f ops/sec\n\n", (double) chmOps / testDurationSeconds);
    }

    @Test
    void compareHitRateWithSimpleLruCache() throws InterruptedException {
        System.out.println("--- Hit-Rate Benchmark (Zipfian Distribution) ---");
        int capacity = 100_000;
        int keyRange = 200_000; // Key range larger than capacity to force evictions
        int testDurationSeconds = 15;

        // Benchmark Cache2M2S
        System.out.println("Benchmarking Cache2M2S...");
        Cache2M2S<Integer, String> cache2m2s = new Cache2M2S<>(capacity);
        long[] cache2m2sStats = runHitRateBenchmark(capacity, keyRange, testDurationSeconds, cache2m2s::put, cache2m2s::get);
        System.out.printf("Cache2M2S Hit Rate: %.2f%% (Hits: %,d, Misses: %,d)\n\n",
                100.0 * cache2m2sStats[0] / (cache2m2sStats[0] + cache2m2sStats[1]), cache2m2sStats[0], cache2m2sStats[1]);

        // Benchmark simple LRU Cache
        System.out.println("Benchmarking Simple LRU Cache...");
        Map<Integer, String> lruCache = Collections.synchronizedMap(new LinkedHashMap<Integer, String>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, String> eldest) {
                return size() > capacity;
            }
        });
        long[] lruStats = runHitRateBenchmark(capacity, keyRange, testDurationSeconds, lruCache::put, lruCache::get);
        System.out.printf("Simple LRU Cache Hit Rate: %.2f%% (Hits: %,d, Misses: %,d)\n\n",
                100.0 * lruStats[0] / (lruStats[0] + lruStats[1]), lruStats[0], lruStats[1]);
    }


    // --- Helper Methods ---

    private long runBenchmark(int keyRange, int duration, BiConsumer<Integer, String> operation) throws InterruptedException {
        AtomicBoolean isRunning = new AtomicBoolean(true);
        AtomicLong totalOps = new AtomicLong(0);
        CountDownLatch latch = new CountDownLatch(numThreads);

        Runnable task = () -> {
            while (isRunning.get()) {
                int key = ThreadLocalRandom.current().nextInt(keyRange);
                operation.accept(key, "value-" + key);
                totalOps.incrementAndGet();
            }
            latch.countDown();
        };

        for (int i = 0; i < numThreads; i++) executor.submit(task);
        Thread.sleep(TimeUnit.SECONDS.toMillis(duration));
        isRunning.set(false);
        latch.await(5, TimeUnit.SECONDS);
        return totalOps.get();
    }

    private long[] runHitRateBenchmark(int capacity, int keyRange, int duration, BiConsumer<Integer, String> put, Function<Integer, String> get) throws InterruptedException {
        AtomicBoolean isRunning = new AtomicBoolean(true);
        AtomicLong hits = new AtomicLong(0);
        AtomicLong misses = new AtomicLong(0);
        CountDownLatch latch = new CountDownLatch(numThreads);

        // Zipfian generator to simulate realistic access patterns
        class SimpleZipfianGenerator {
            private final ThreadLocalRandom random = ThreadLocalRandom.current();
            private final double skew = 0.99;
            private final double bottom = 1.0 / Math.pow(keyRange, 1.0 - skew);
            public int nextInt() {
                double r = random.nextDouble() * bottom;
                return (int) Math.floor(Math.pow(r, 1.0 / (1.0 - skew)));
            }
        }
        final SimpleZipfianGenerator zipf = new SimpleZipfianGenerator();

        Runnable task = () -> {
            while (isRunning.get()) {
                int key = zipf.nextInt();
                if (ThreadLocalRandom.current().nextInt(10) < 8) { // 80% reads
                    if (get.apply(key) != null) {
                        hits.incrementAndGet();
                    } else {
                        misses.incrementAndGet();
                    }
                } else { // 20% writes
                    put.accept(key, "value-" + key);
                }
            }
            latch.countDown();
        };

        for (int i = 0; i < numThreads; i++) executor.submit(task);
        Thread.sleep(TimeUnit.SECONDS.toMillis(duration));
        isRunning.set(false);
        latch.await(5, TimeUnit.SECONDS);
        return new long[]{hits.get(), misses.get()};
    }

    // --- Functional Interfaces for Lambdas ---
    @FunctionalInterface
    interface BiConsumer<T, U> { void accept(T t, U u); }
    @FunctionalInterface
    interface Function<T, R> { R apply(T t); }
}
