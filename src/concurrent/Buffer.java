package concurrent;

import jdk.internal.vm.annotation.Contended;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 2M2S doc
 * A bounded buffer for temporarily storing nodes before they are processed by the cache.
 * This buffer is designed for high-throughput, low-latency scenarios and uses a combination of
 * atomic operations and a lock to manage concurrent access.
 *
 * @param <K> the type of keys maintained by the cache
 * @param <V> the type of mapped values
 */
class Buffer<K, V> {
    /**
     * The maximum number of items that can be drained from the buffer at once.
     */
    final int BUFFER_LIMIT = 4096;
    private final int capacity;
    private final int mask;

    /**
     * The underlying array buffer. Using AtomicReferenceArray ensures memory visibility
     * (volatile/release/acquire semantics) for its elements.
     */
    private final AtomicReferenceArray<Node<K, V>> buffer;
    private final Cache2M2S<K, V> cache;
    private final ReentrantLock bufferLock = new ReentrantLock();

    /**
     * The index for the next write operation. Padded to prevent false sharing.
     */
    @Contended
    private final AtomicLong writeIndex = new AtomicLong(0);

    /**
     * The index for the next read operation. Volatile to ensure visibility to producer threads.
     * Padded to prevent false sharing.
     */
    @Contended
    private volatile long readIndex = 0;

    /**
     * Constructs a new Buffer.
     * @param cache The parent cache instance.
     */
    public Buffer(Cache2M2S<K, V> cache) {
        this.cache = cache;
        int powerOfTwo = Integer.highestOneBit(BUFFER_LIMIT - 1) << 1;
        this.capacity = powerOfTwo;
        this.mask = powerOfTwo - 1;
        this.buffer = new AtomicReferenceArray<>(powerOfTwo);
    }

    /**
     * Adds a node to the buffer.
     * If the buffer is getting full, this method may trigger a drain operation.
     * This is a wait-free algorithm for the producer path under normal conditions.
     *
     * @param node the node to add
     */
    public void offer(Node<K, V> node) {
        long currentWrite;
        long currentRead;

        while (true) {
            currentWrite = writeIndex.get();
            currentRead = readIndex;
            long currentSize = currentWrite - currentRead;

            if (currentSize >= capacity) {
                if (bufferLock.tryLock()) {
                    try {
                        drain();
                    } finally {
                        bufferLock.unlock();
                    }
                } else {
                    Thread.onSpinWait();
                }
                continue;
            }

            if (currentSize >= capacity / 2) {
                if (bufferLock.tryLock()) {
                    try {
                        drain();
                    } finally {
                        bufferLock.unlock();
                    }
                    continue;
                }
            }
            if (writeIndex.compareAndSet(currentWrite, currentWrite + 1)) {
                break;
            }
        }
        int index = (int) (currentWrite & mask);
        buffer.lazySet(index, node);
    }

    /**
     * Drains nodes from the buffer and processes them in the main cache.
     * This method is called by a thread that successfully acquires the bufferLock.
     */
    public void drain() {
        long currentRead = readIndex;
        long currentWrite = writeIndex.get();
        int drained = 0;

        while (currentRead < currentWrite && drained < BUFFER_LIMIT) {
            int index = (int) (currentRead & mask);

            Node<K, V> node = buffer.get(index);


            if (node == null) {
                Thread.onSpinWait();
                continue;
            }

            buffer.lazySet(index, null);

            cache.processNode(node);

            currentRead++;
            drained++;

            if ((drained & 63) == 0) {
                readIndex = currentRead;
            }
        }
        readIndex = currentRead;
    }
}
