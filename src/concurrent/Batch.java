package concurrent;

/**
 * 2M2S doc
 * A thread-local batch for collecting hash codes before incrementing their
 * frequency in the {@link Sketch}. This helps to reduce contention on the sketch
 * by batching updates from a single thread.
 *
 * This class is intended to be used with {@link ThreadLocal}, so it does not
 * require any internal thread safety mechanisms.
 *
 * @param <K> The type of the key, although it is not directly used, it's kept for
 *           consistency with the cache's generic types.
 */
class Batch<K> {
    final int CAPACITY = 256;
    @SuppressWarnings("unchecked")
    private final int[] keys = new int[CAPACITY];
    private int size = 0;

    /**
     * Adds a hash code to the batch. If the batch is full, the hash is ignored.
     * @param hash The hash code to add.
     */
    void add(int hash) {
        if (size < keys.length) {
            keys[size++] = hash;
        }
    }

    /**
     * Flushes all hash codes in the batch to the sketch, incrementing their
     * frequencies, and then resets the batch.
     * @param sketch The sketch to update.
     */
    void flush(Sketch sketch) {
        for (int i = 0; i < size; i++) {
            sketch.increment(keys[i]);
        }
        reset();
    }

    /**
     * Adds a hash code to the batch and flushes the batch to the sketch if it is full.
     * @param hash The hash code to add.
     * @param sketch The sketch to update if the batch becomes full.
     */
    void increment(int hash, Sketch sketch) {
        add(hash);
        if (full()) {
            flush(sketch);
        }
    }

    /**
     * Checks if the batch is full.
     * @return {@code true} if the batch is full, {@code false} otherwise.
     */
    boolean full() {
        return size == keys.length;
    }

    /**
     * Resets the batch, clearing it for reuse.
     */
    void reset() {
        size = 0;
    }
}
