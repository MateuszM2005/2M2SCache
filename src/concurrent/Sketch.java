package concurrent;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 2M2S doc
 * A Count-Min Sketch data structure used for frequency estimation of elements.
 * This implementation is specifically designed for concurrent access and uses
 * 4-bit counters packed into an AtomicLongArray to save space. It employs four
 * hash functions to update and estimate the frequency of an item.
 *
 * Each of the four counters for an item is addressed by an independent
 * multiplicative hash, so two items that collide in one row are very unlikely to
 * collide in the others. That independence is what makes the minimum of the four
 * a better estimate than any single counter.
 *
 * The sketch periodically resets by halving all counters to adapt to changes
 * in access patterns over time (a form of aging).
 */
class Sketch {
    private static final int ROWS = 4;

    /**
     * One 64-bit multiplier per row. Distinct odd constants with well mixed bits, so
     * {@link #indexOf} produces uncorrelated indices for the same item.
     */
    private static final long[] SEED = {
            0xc3a5c85c97cb3127L,
            0xb492b66fbe98f273L,
            0x9ae16a3b2f90404fL,
            0xcbf29ce484222325L
    };

    private static final long RESET_MASK = 0x7777777777777777L;

    private final int sampleSize;
    private final int tableMask;
    private final AtomicLongArray table;
    private final AtomicLong size;
    private final ReentrantLock resetLock = new ReentrantLock();

    /**
     * Constructs a new Sketch.
     * @param expectedElements The expected number of distinct elements to be tracked.
     */
    Sketch(int expectedElements) {
        this.size = new AtomicLong(0);
        int tableSize = (expectedElements < 2) ? 2 :
                Integer.highestOneBit(expectedElements - 1) << 1;
        this.table = new AtomicLongArray((tableSize >> 4) + 1);
        this.tableMask = tableSize - 1;
        this.sampleSize = expectedElements * 10;
    }

    /**
     * Adds to the frequency count for an element, incrementing each of its four counters.
     * Does not update the sample total; callers batching several items should report the
     * total once via {@link #addSamples(int)}.
     *
     * @param hash  The hash code of the element.
     * @param count How many accesses to record.
     */
    void increment(int hash, int count) {
        int spread = spread(hash);
        for (int row = 0; row < ROWS; row++) {
            incrementCounter(indexOf(spread, row), count);
        }
    }

    /**
     * Records that {@code count} accesses were observed, and resets the sketch once the
     * running total passes the sample size.
     *
     * @param count How many accesses were recorded since the last call.
     */
    void addSamples(int count) {
        if (size.addAndGet(count) >= sampleSize && resetLock.tryLock()) {
            try {
                if (size.get() >= sampleSize) {
                    reset();
                }
            } finally {
                resetLock.unlock();
            }
        }
    }

    /**
     * Estimates the frequency of an element with the given hash.
     * The estimated frequency is the minimum of the values of the four counters
     * corresponding to the element.
     *
     * @param hash The hash code of the element.
     * @return The estimated frequency.
     */
    public int frequency(int hash) {
        int spread = spread(hash);
        int min = Integer.MAX_VALUE;
        for (int row = 0; row < ROWS; row++) {
            min = Math.min(min, readCounter(indexOf(spread, row)));
        }
        return min;
    }

    /**
     * Mixes a key's hash code so that poorly distributed hash codes, such as the identity
     * hash of small integers, do not land in a narrow band of counters.
     */
    static int spread(int hash) {
        hash = ((hash >>> 16) ^ hash) * 0x45d9f3b;
        hash = ((hash >>> 16) ^ hash) * 0x45d9f3b;
        return (hash >>> 16) ^ hash;
    }

    /**
     * Maps an already spread hash to a counter index for one row.
     */
    private int indexOf(int spread, int row) {
        long hash = (spread + SEED[row]) * SEED[row];
        hash += hash >>> 32;
        return ((int) hash) & tableMask;
    }

    /**
     * Adds to a specific 4-bit counter, saturating at 15.
     * This is a lock-free operation using CAS on the containing long.
     *
     * @param index The index of the counter to increment.
     * @param count How much to add.
     */
    private void incrementCounter(int index, int count) {
        int block = index >>> 4;
        int offset = (index & 15) << 2;
        long mask = 0xFL << offset;

        while (true) {
            long current = table.get(block);
            long value = (current & mask) >>> offset;

            if (value >= 15) {
                return;
            }

            long updated = Math.min(15, value + count);
            long next = (current & ~mask) | (updated << offset);
            if (table.compareAndSet(block, current, next)) {
                return;
            }
        }
    }

    /**
     * Reads the value of a specific 4-bit counter.
     * @param index The index of the counter to read.
     * @return The value of the counter.
     */
    private int readCounter(int index) {
        int block = index >>> 4;
        int offset = (index & 15) << 2;
        return (int) ((table.get(block) >>> offset) & 0xF);
    }

    /**
     * Resets the sketch by halving all counters and the total size.
     * This helps the sketch to forget old entries and adapt to new access patterns.
     */
    private void reset() {
        for (int i = 0; i < table.length(); i++) {
            table.set(i, (table.get(i) >>> 1) & RESET_MASK);
        }
        size.getAndUpdate(current -> current >>> 1);
    }
}
