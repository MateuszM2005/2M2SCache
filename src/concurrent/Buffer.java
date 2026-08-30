package concurrent;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 2M2S doc
 * A bounded buffer for temporarily storing nodes before they are processed by the cache.
 * This buffer is designed for high-throughput, low-latency scenarios and uses a combination of
 * atomic operations and a lock to manage concurrent access.
 *
 * The buffer is split into several independent rings. A thread keeps the same ring for its
 * lifetime, so the counter it advances on every operation stays in its own core's cache
 * instead of being fought over by every thread in the system. A single shared write counter
 * serializes producers on one cache line before any queue work happens, which showed up as
 * throughput falling off past four threads.
 *
 * Draining stays serialized under one lock and sweeps every ring, so maintenance is still
 * performed by a single thread at a time. Nodes are therefore processed in per-ring order
 * rather than in global arrival order, which perturbs recency slightly and costs nothing:
 * the queues are an approximation of access order in any case.
 *
 * @param <K> the type of keys maintained by the cache
 * @param <V> the type of mapped values
 */
class Buffer<K, V> {
    private static final int STRIDE_SHIFT = 3;

    private final int rings;
    private final int ringCapacity;
    private final int slotMask;

    /**
     * The underlying array buffer, holding every ring's slots end to end. Using
     * AtomicReferenceArray ensures memory visibility (volatile/release/acquire semantics)
     * for its elements.
     */
    private final AtomicReferenceArray<Node<K, V>> buffer;

    /**
     * Per-ring cursors, one per cache line. Producers advance {@code writeIndex}; the thread
     * holding the drain lock advances {@code readIndex}.
     */
    private final AtomicLongArray writeIndex;
    private final AtomicLongArray readIndex;

    private final Cache2M2S<K, V> cache;

    /**
     * Shared with every other buffer belonging to the same cache. Draining mutates the queues,
     * so exactly one thread may drain any buffer at a time.
     */
    private final ReentrantLock bufferLock;

    /**
     * When true, an offer to a full ring is discarded instead of waiting for space. Safe only
     * for records that merely refresh recency: a record for a new or deleted node has to be
     * processed, or the node is left in the map without a queue, or in a queue without a map
     * entry, and never reclaimed.
     */
    private final boolean lossy;

    /**
     * Counts discarded records, for tests and diagnostics.
     */
    private final AtomicLong dropped = new AtomicLong();

    /**
     * Hands out rings round-robin, so the first threads to arrive land on distinct rings.
     */
    private final AtomicInteger nextRing = new AtomicInteger();

    /**
     * The calling thread's ring, fixed on first use. A stable choice is what keeps the
     * cursor's cache line local to one core; picking a ring per call would move it every time.
     */
    private final ThreadLocal<Integer> ring;

    /**
     * Constructs a new Buffer.
     *
     * @param cache The parent cache instance.
     * @param bufferLock The maintenance lock shared by all of the cache's buffers.
     * @param lossy Whether a full ring may discard records rather than wait for space.
     * @param rings How many independent rings. Must be a power of two, at least 1.
     * @param ringCapacity Slots per ring. Must be a power of two, at least 1.
     */
    public Buffer(Cache2M2S<K, V> cache, ReentrantLock bufferLock, boolean lossy,
                  int rings, int ringCapacity) {
        this.cache = cache;
        this.bufferLock = bufferLock;
        this.lossy = lossy;
        this.rings = rings;
        this.ringCapacity = ringCapacity;
        this.slotMask = ringCapacity - 1;
        this.buffer = new AtomicReferenceArray<>(rings * ringCapacity);
        this.writeIndex = new AtomicLongArray(rings << STRIDE_SHIFT);
        this.readIndex = new AtomicLongArray(rings << STRIDE_SHIFT);

        final AtomicInteger counter = nextRing;
        final int ringMask = rings - 1;
        this.ring = ThreadLocal.withInitial(() -> counter.getAndIncrement() & ringMask);
    }

    /**
     * Adds a node to the buffer.
     * If the calling thread's ring is getting full, this method may trigger a drain operation.
     * This is a wait-free algorithm for the producer path under normal conditions.
     *
     * @param node the node to add
     * @return true if the node was recorded, false if a lossy buffer discarded it
     */
    public boolean offer(Node<K, V> node) {
        int myRing = ring.get();
        int cursor = myRing << STRIDE_SHIFT;
        long currentWrite;

        while (true) {
            currentWrite = writeIndex.get(cursor);
            long currentSize = currentWrite - readIndex.get(cursor);

            if (currentSize >= ringCapacity) {
                boolean drainedSomething = false;
                if (bufferLock.tryLock()) {
                    try {
                        drainedSomething = cache.drainBuffers() > 0;
                    } finally {
                        bufferLock.unlock();
                    }
                }
                // A lossy buffer never waits. Whoever holds the lock is already draining, and
                // one missed recency record costs less than making this thread stand still.
                if (lossy) {
                    if (!drainedSomething) {
                        dropped.incrementAndGet();
                        return false;
                    }
                } else if (!drainedSomething) {
                    // No progress means the oldest slot is still unpublished. Back off rather
                    // than re-acquiring the lock in a tight loop.
                    Thread.onSpinWait();
                }
                continue;
            }

            if (currentSize >= ringCapacity / 2) {
                if (bufferLock.tryLock()) {
                    try {
                        cache.drainBuffers();
                    } finally {
                        bufferLock.unlock();
                    }
                    continue;
                }
            }
            if (writeIndex.compareAndSet(cursor, currentWrite, currentWrite + 1)) {
                break;
            }
        }
        buffer.lazySet(slotOf(myRing, currentWrite), node);
        return true;
    }

    /**
     * @return how many records this buffer has discarded because a ring was full
     */
    public long dropped() {
        return dropped.get();
    }

    /**
     * Drains every ring and processes the nodes in the main cache.
     * This method is called by a thread that successfully acquires the bufferLock.
     *
     * @return how many nodes were processed
     */
    public int drain() {
        int drained = 0;
        for (int myRing = 0; myRing < rings; myRing++) {
            drained += drainRing(myRing);
        }
        return drained;
    }

    /**
     * Drains one ring. A ring holds at most {@code ringCapacity} entries, so this needs no
     * separate work limit.
     *
     * @return how many nodes were processed
     */
    private int drainRing(int myRing) {
        int cursor = myRing << STRIDE_SHIFT;
        long currentRead = readIndex.get(cursor);
        long currentWrite = writeIndex.get(cursor);
        int drained = 0;

        while (currentRead < currentWrite) {
            int index = slotOf(myRing, currentRead);

            Node<K, V> node = buffer.get(index);

            // The producer that claimed this slot has not published its node yet. Waiting for it
            // would stall every other thread, since they cannot proceed past a full ring while
            // this drain holds the lock. Stop here and let the next drain pick the entry up.
            if (node == null) {
                break;
            }

            buffer.lazySet(index, null);

            cache.processNode(node);

            currentRead++;
            drained++;

            // Publish progress mid-drain so a producer waiting on a full ring can continue.
            if ((drained & 63) == 0) {
                readIndex.set(cursor, currentRead);
            }
        }
        readIndex.set(cursor, currentRead);
        return drained;
    }

    /**
     * Maps a ring's write or read position to an index in the shared slot array.
     */
    private int slotOf(int myRing, long position) {
        return (myRing * ringCapacity) + (int) (position & slotMask);
    }
}
