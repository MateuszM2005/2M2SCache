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
 */
@Timeout(60)
public class SmallTests {

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
    void testValueUpdate() throws InterruptedException {
        Cache2M2S<String, Integer> cache = new Cache2M2S<>(100);
        String key = "test-key";

        cache.put(key, 1);
        Thread.sleep(200); // Wait for drain
        assertEquals(1, cache.get(key), "Initial value should be 1.");

        cache.put(key, 2);
        Thread.sleep(200); // Wait for drain
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
    void testEvictionAndReinsertion() throws InterruptedException {
        Cache2M2S<Integer, Integer> cache = new Cache2M2S<>(100000);
        for (int i = 0; i < 150000; i++) { // Fill beyond capacity
            cache.put(i, i);
        }
        Thread.sleep(200); // Wait for drain

        assertNull(cache.get(0), "Item 0 should have been evicted.");
        cache.put(0, 100);
        Thread.sleep(200); // Wait for drain
        assertEquals(100, cache.get(0), "Re-inserted item 0 should have the new value.");
    }

    @Test
    @DisplayName("Removing an item correctly decreases cache size")
    void testRemoveReducesSize() throws InterruptedException {
        Cache2M2S<Integer, Integer> cache = new Cache2M2S<>(100);
        cache.put(1, 1);
        cache.put(2, 2);
        Thread.sleep(200); // Wait for drain
        assertEquals(2, cache.size());

        cache.remove(1);
        Thread.sleep(200); // Wait for drain
        assertEquals(1, cache.size(), "Size should be 1 after removing an item.");
        assertNull(cache.get(1), "Removed item should not be accessible.");
        assertNotNull(cache.get(2), "Other item should still be accessible.");
    }

    @Test
    @DisplayName("Probation item is promoted to protected on second access")
    void testPromotionToProtected() throws InterruptedException {
        // With capacity 100: window=1, protected=80, probation=19
        Cache2M2S<Integer, Integer> cache = new Cache2M2S<>(100);

        // 1. Fill window (key 0) and move it to probation
        cache.put(0, 0);
        cache.put(1, 1); // This evicts 0 from window to probation
        Thread.sleep(200);

        // 2. Access key 0 again to trigger promotion
        cache.get(0);
        Thread.sleep(200);

        // 3. Fill probation and window to try and evict 0
        for (int i = 2; i < 50; i++) {
            cache.put(i, i);
        }
        Thread.sleep(500);

        // 4. Verify key 0 is still present (it was protected)
        assertNotNull(cache.get(0), "Item 0 should be in the protected space and not evicted.");
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
        final int actualCapacity = (int) (requestedCapacity * 1.09); // Approximate real capacity
        final int numThreads = 8;
        final int putsPerThread = 2_000; // 16,000 puts > capacity
        final Cache2M2S<String, Integer> cache = new Cache2M2S<>(requestedCapacity);
        final CountDownLatch latch = new CountDownLatch(numThreads);
        final AtomicInteger errors = new AtomicInteger(0);

        Runnable task = () -> {
            try {
                for (int i = 0; i < putsPerThread; i++) {
                    String key = "T" + Thread.currentThread().getId() + "-K" + i;
                    cache.put(key, i);
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
        assertEquals(0, errors.get(), "Exceptions were thrown during concurrent eviction.");
        assertTrue(cache.size() <= actualCapacity, "Cache size (" + cache.size() + ") exceeded its actual capacity (" + actualCapacity + ").");
    }

    @RepeatedTest(10)
    @DisplayName("Mixed concurrent operations (put, get, remove) are stable")
    void testConcurrentPutGetAndRemove() throws InterruptedException {
        final int requestedCapacity = 10_000;
        final int actualCapacity = (int) (requestedCapacity + 4096); // Capacity + buffer
        final int numThreads = 8;
        final int opsPerThread = 20_000;
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
        assertTrue(cache.size() <= actualCapacity, "Cache size (" + cache.size() + ") exceeded its actual capacity (" + actualCapacity + ").");
    }
}
