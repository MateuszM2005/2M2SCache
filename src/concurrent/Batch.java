package concurrent;

/**
 * 2M2S doc
 * A thread-local batch of observed accesses, applied to the {@link Sketch} in one go.
 *
 * Repeated accesses to the same key are folded into a single entry, so a burst on a few
 * hot keys costs a handful of compare-and-set operations instead of one per access. The
 * sample total is reported once per flush rather than once per access, which keeps the
 * sketch's single shared counter off the hot path.
 *
 * This class is intended to be used with {@link ThreadLocal}, so it does not
 * require any internal thread safety mechanisms.
 */
class Batch {
    /**
     * Slots in the fold table. A power of two, so {@link #MASK} can replace a modulo.
     */
    private static final int CAPACITY = 256;
    private static final int MASK = CAPACITY - 1;
    /**
     * Flush once half the slots are taken, which keeps the linear probe in
     * {@link #add} short and guarantees a free slot for every insert.
     */
    private static final int MAX_DISTINCT = CAPACITY / 2;
    /**
     * Flush after this many accesses even when they all fold into one slot, so a
     * single hot key cannot delay the sketch indefinitely.
     */
    private static final int MAX_TOTAL = CAPACITY;

    private final int[] hashes = new int[CAPACITY];
    private final int[] counts = new int[CAPACITY];
    private int distinct;
    private int total;

    /**
     * Records one access, flushing to the sketch if the batch is full.
     *
     * @param hash The hash code to record.
     * @param sketch The sketch to update once the batch fills up.
     */
    void increment(int hash, Sketch sketch) {
        add(hash);
        if (distinct >= MAX_DISTINCT || total >= MAX_TOTAL) {
            flush(sketch);
        }
    }

    /**
     * Folds an access into the table, keeping one entry per distinct hash.
     * A zero count marks a free slot, so any hash value is storable.
     */
    private void add(int hash) {
        int slot = Sketch.spread(hash) & MASK;
        while (counts[slot] != 0) {
            if (hashes[slot] == hash) {
                counts[slot]++;
                total++;
                return;
            }
            slot = (slot + 1) & MASK;
        }
        hashes[slot] = hash;
        counts[slot] = 1;
        distinct++;
        total++;
    }

    /**
     * Applies every folded access to the sketch, then clears the batch for reuse.
     *
     * @param sketch The sketch to update.
     */
    void flush(Sketch sketch) {
        if (total == 0) {
            return;
        }
        for (int slot = 0; slot < CAPACITY; slot++) {
            int count = counts[slot];
            if (count != 0) {
                sketch.increment(hashes[slot], count);
                counts[slot] = 0;
            }
        }
        sketch.addSamples(total);
        distinct = 0;
        total = 0;
    }
}
