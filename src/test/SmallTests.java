package test;

import concurrent.Cache2M2S;
import org.junit.jupiter.api.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2M2S doc
 * Small-scale concurrency and correctness tests for Cache2M2S.
 * These tests verify thread safety and edge cases under contention
 * with a small data set, forcing more interactions with eviction and promotion logic.
 *
 * No test waits for the cache to settle. Reads and writes hit the map synchronously, and
 * queue maintenance is performed by the calling thread when it drains the buffer, so there
 * is no background worker whose progress a sleep could wait on.
 */
@Timeout(60)
public class SmallTests {

    /**
     * An entry is in the map as soon as its put returns, but only becomes a candidate for
     * eviction once a drain moves it into a queue. The write buffer holds at most 4096
     * undrained admissions; the read buffer holds recency records that do not add map entries.
     */
    private static final int UNDRAINED_SLACK = 4096;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        int numThreads = Math.max(4, Runtime.getRuntime().availableProcessors());
        executor = Executors.newFixedThreadPool(numThreads);
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    // --- Single-Threaded Correctness Tests ---

    @Test
    @DisplayName("Putting a new value for an existing key updates it")
    void testValueUpdate() {
        Cache2M2S<String, Integer> cache = new Cache2M2S<>(100);
        String key = "test-key";

        cache.put(key, 1);
        assertEquals(1, cache.get(key), "Initial value should be 1.");

        cache.put(key, 2);
        assertEquals(2, cache.get(key), "Value should be updated to 2.");
        assertEquals(1, cache.size(), "Cache size should remain 1 after update.");
    }

    @Test
    @DisplayName("Cache should reject null keys and values")
    void testNullHandling() {
        Cache2M2S<String, String> cache = new Cache2M2S<>(100);

        assertThrows(NullPointerException.class, () -> cache.put(null, "value"), "Putting a null key should throw NullPointerException.");
        assertThrows(NullPointerException.class, () -> cache.get(null), "Getting a null key should throw NullPointerException.");
    }

    @Test
    @DisplayName("An evicted item can be successfully re-inserted")
    void testEvictionAndReinsertion() {
        Cache2M2S<Integer, Integer> cache = new Cache2M2S<>(100000);
        for (int i = 0; i < 150000; i++) { // Fill beyond capacity
            cache.put(i, i);
        }

        assertNull(cache.get(0), "Item 0 should have been evicted.");
        cache.put(0, 100);
        assertEquals(100, cache.get(0), "Re-inserted item 0 should have the new value.");
    }

    @Test
    @DisplayName("Removing an item correctly decreases cache size")
    void testRemoveReducesSize() {
        Cache2M2S<Integer, Integer> cache = new Cache2M2S<>(100);
        cache.put(1, 1);
        cache.put(2, 2);
        assertEquals(2, cache.size());

        cache.remove(1);
        assertEquals(1, cache.size(), "Size should be 1 after removing an item.");
        assertNull(cache.get(1), "Removed item should not be accessible.");
        assertNotNull(cache.get(2), "Other item should still be accessible.");
    }

    @Test
    @DisplayName("A read-through workload reaches full occupancy and a near-perfect hit rate")
    void testReadThroughReachesFullOccupancy() {
        final int capacity = 10_000; // the constructor's floor
        final int workingSet = 9_000; // fits inside capacity, so every key should end up resident
        final int passes = 40;
        Cache2M2S<Integer, Integer> cache = new Cache2M2S<>(capacity);

        // Re-inserting on a miss interleaves fresh admissions, which drive eviction, with
        // delayed re-accesses, which are what promote an entry out of probation. Only that
        // combination lets the protected region fill.
        double hitRate = 0.0;
        for (int pass = 0; pass < passes; pass++) {
            int hits = 0;
            for (int key = 0; key < workingSet; key++) {
                if (cache.get(key) != null) {
                    hits++;
                } else {
                    cache.put(key, key);
                }
            }
            hitRate = 100.0 * hits / workingSet;
        }

        assertTrue(hitRate > 95.0,
                "A working set of " + workingSet + " in a cache of " + capacity
                        + " should be almost entirely resident, but the hit rate settled at " + hitRate + "%.");
        assertTrue(cache.size() > workingSet * 0.85,
                "Cache holds only " + cache.size() + " of the " + workingSet + " keys in the working set.");
        assertTrue(cache.size() <= capacity + UNDRAINED_SLACK,
                "Cache size (" + cache.size() + ") exceeded capacity plus undrained entries.");
    }

    @Test
    @DisplayName("Repeatedly accessed keys survive a flood of single-access keys")
    void testFrequentKeysSurviveFlood() {
        final int capacity = 10_000;
        final int hotKeys = 100;
        final int touchesPerHotKey = 20;
        Cache2M2S<Integer, Integer> cache = new Cache2M2S<>(capacity);

        // Negative keys keep the hot set disjoint from the flood below.
        for (int round = 0; round < touchesPerHotKey; round++) {
            for (int key = 1; key <= hotKeys; key++) {
                cache.put(-key, key);
            }
        }

        for (int key = 0; key < capacity * 5; key++) {
            cache.put(key, key);
        }

        int survivors = 0;
        for (int key = 1; key <= hotKeys; key++) {
            if (cache.get(-key) != null) {
                survivors++;
            }
        }

        assertTrue(survivors >= hotKeys * 0.9,
                "Only " + survivors + " of " + hotKeys + " frequently accessed keys survived a flood of "
                        + (capacity * 5) + " single-access keys.");
    }


    // --- Concurrency Tests ---

    @RepeatedTest(10)
    @DisplayName("Concurrent put and get operations do not cause crashes")
    void testConcurrentPutAndGet() throws InterruptedException {
        final int requestedCapacity = 10_000;
        final int numThreads = 8;
        final int opsPerThread = 10_000;
        final Cache2M2S<Integer, String> cache = new Cache2M2S<>(requestedCapacity);
        final CountDownLatch latch = new CountDownLatch(numThreads);
        final AtomicInteger errors = new AtomicInteger(0);

        Runnable task = () -> {
            try {
                for (int i = 0; i < opsPerThread; i++) {
                    int key = ThreadLocalRandom.current().nextInt(0, requestedCapacity);
                    if (ThreadLocalRandom.current().nextBoolean()) {
                        cache.put(key, "thread-val-" + key);
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

        assertTrue(latch.await(30, TimeUnit.SECONDS), "Test timed out");
        assertEquals(0, errors.get(), "Exceptions were thrown during concurrent execution.");
    }

    @RepeatedTest(10)
    @DisplayName("Concurrent puts correctly force eviction without errors")
    void testConcurrentEviction() throws InterruptedException {
        final int requestedCapacity = 10_000;
        final int numThreads = 8;
        final int putsPerThread = 2_000; // 16,000 puts > capacity
        final int maxSize = requestedCapacity + UNDRAINED_SLACK + numThreads;
        final Cache2M2S<String, Integer> cache = new Cache2M2S<>(requestedCapacity);
        final CountDownLatch latch = new CountDownLatch(numThreads);
        final AtomicInteger errors = new AtomicInteger(0);

        for (int thread = 0; thread < numThreads; thread++) {
            final int id = thread;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < putsPerThread; i++) {
                        cache.put("T" + id + "-K" + i, i);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "Test timed out");
        assertEquals(0, errors.get(), "Exceptions were thrown during concurrent eviction.");
        assertTrue(cache.size() <= maxSize,
                "Cache size (" + cache.size() + ") exceeded its maximum (" + maxSize + ").");
    }

    @RepeatedTest(10)
    @DisplayName("Mixed concurrent operations (put, get, remove) are stable")
    void testConcurrentPutGetAndRemove() throws InterruptedException {
        final int requestedCapacity = 10_000;
        final int numThreads = 8;
        final int opsPerThread = 20_000;
        final int maxSize = requestedCapacity + UNDRAINED_SLACK + numThreads;
        final Cache2M2S<Integer, String> cache = new Cache2M2S<>(requestedCapacity);
        final CountDownLatch latch = new CountDownLatch(numThreads);
        final AtomicInteger errors = new AtomicInteger(0);

        Runnable task = () -> {
            try {
                for (int i = 0; i < opsPerThread; i++) {
                    int key = ThreadLocalRandom.current().nextInt(0, requestedCapacity * 2);
                    int operation = ThreadLocalRandom.current().nextInt(10); // 40% put, 50% get, 10% remove

                    if (operation < 4) {
                        cache.put(key, "value-" + key);
                    } else if (operation < 9) {
                        cache.get(key);
                    } else {
                        cache.remove(key);
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

        assertTrue(latch.await(45, TimeUnit.SECONDS), "Test timed out");
        assertEquals(0, errors.get(), "Exceptions were thrown during mixed operations.");
        assertTrue(cache.size() <= maxSize,
                "Cache size (" + cache.size() + ") exceeded its maximum (" + maxSize + ").");
    }
}
