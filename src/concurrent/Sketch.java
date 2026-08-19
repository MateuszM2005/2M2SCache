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
 * The sketch periodically resets by halving all counters to adapt to changes
 * in access patterns over time (a form of aging).
 */
class Sketch {
    private static final int SEED_1 = 0xc3a5c85c;
    private static final int SEED_2 = 0x811c9dc5;

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
     * Increments the frequency count for an element with the given hash.
     * This involves incrementing four different counters in the sketch.
     * If the total number of increments exceeds the sample size, a reset is triggered.
     *
     * @param hash The hash code of the element.
     */
    public void increment(int hash) {
        if (size.get() >= sampleSize) {
            if (resetLock.tryLock()) {
                try {
                    if (size.get() >= sampleSize) {
                        reset();
                    }
                } finally {
                    resetLock.unlock();
                }
            }
        }
        size.incrementAndGet();

        int start = hash & tableMask;
        int index0 = start;
        int index1 = (start + SEED_1) & tableMask;
        int index2 = (start + SEED_2) & tableMask;
        int index3 = (start + (SEED_1 ^ SEED_2)) & tableMask;

        incrementCounter(index0);
        incrementCounter(index1);
        incrementCounter(index2);
        incrementCounter(index3);
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
        int start = hash & tableMask;
        int index0 = start;
        int index1 = (start + SEED_1) & tableMask;
        int index2 = (start + SEED_2) & tableMask;
        int index3 = (start + (SEED_1 ^ SEED_2)) & tableMask;

        int min = readCounter(index0);
        min = Math.min(min, readCounter(index1));
        min = Math.min(min, readCounter(index2));
        min = Math.min(min, readCounter(index3));
        return min;

    }

    /**
     * Increments a specific 4-bit counter.
     * This is a lock-free operation using CAS on the containing long.
     *
     * @param index The index of the counter to increment.
     */
    private void incrementCounter(int index) {
        int block = index >>> 4;
        int offset = (index & 15) << 2;
        long mask = 0xFL << offset;

        while (true) {
            long current = table.get(block);
            long count = (current & mask) >>> offset;

            if (count < 15) {
                long next = (current & ~mask) | ((count + 1) << offset);
                if (table.compareAndSet(block, current, next)) {
                    return;
                }
            } else {
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
            table.set(i, (table.get(i) >>> 1) & 0x7777777777777777L);
        }
        size.set(size.get() >>> 1);
    }
}
