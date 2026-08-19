package concurrent;

import java.util.concurrent.ConcurrentHashMap;


/**
 * <pre>2M2S©</pre>
 * A concurrent cache implementation with a three queue policy.
 * This cache is designed to be highly concurrent and scalable, using a combination of
 * techniques to minimize lock contention and maximize throughput. It is inspired by
 * algorithms like LIRS and aims to provide better hit rates than traditional LRU
 * for many access patterns.
 *
 * The cache is divided into three main regions:
 * <ul>
 *     <li><b>Window:</b> A small, temporary space for new entries.</li>
 *     <li><b>Probation:</b> The main space for entries that have been accessed at least once.
 *         Entries in this region are candidates for eviction.</li>
 *     <li><b>Protected:</b> A region for frequently accessed items. Entries are promoted
 *         from probation to protected. An entry in protected is demoted to probation
 *         if it's not accessed for a while.</li>
 * </ul>
 *
 * Updates to the cache are batched through a {@link Buffer} to reduce contention.
 * A {@link Sketch} data structure is used to estimate access frequency, which helps
 * in making eviction decisions.
 *
 * @param <K> the type of keys maintained by this cache
 * @param <V> the type of mapped values
 */
public class Cache2M2S<K, V> {
    final int LOC_UNLINKED = -1;
    final int LOC_NEW = 0;
    final int LOC_WINDOW = 1;
    final int LOC_PROBATION = 2;
    final int LOC_PROTECTED = 3;

    private final int MAX_SIZE;
    private final int WINDOW_SIZE;
    private final int PROBATION_SIZE;
    private final int PROTECTION_SIZE;

    private final ConcurrentHashMap<K, Node<K, V>> cache;

    private final Queue<K, V> protectedLRU;
    private final Queue<K, V> probationLRU;
    private final Queue<K, V> windowLRU;

    private final Buffer<K, V> buffer;


    private final ThreadLocal<Batch<K>> batch =
            ThreadLocal.withInitial(Batch::new);
    private final Sketch sketch;

    /**
     * Default constructor. Creates a cache with a default maximum size of 1,000,000.
     */
    public Cache2M2S() {
        this.MAX_SIZE = 1000000;
        this.WINDOW_SIZE = 10000;
        this.PROBATION_SIZE = 190000;
        this.PROTECTION_SIZE = 800000;

        this.cache = new ConcurrentHashMap<>(MAX_SIZE);
        this.protectedLRU = new Queue<>(PROTECTION_SIZE);
        this.probationLRU = new Queue<>(PROBATION_SIZE);
        this.windowLRU = new Queue<>(WINDOW_SIZE);

        this.sketch = new Sketch(MAX_SIZE * 4);
        this.buffer = new Buffer<>(this);
    }

    /**
     * Creates a cache with a specified maximum capacity.
     * The capacity is distributed among the window, probation, and protected regions.
     * @param MAX_CAPACITY The maximum number of elements in the cache.
     */
    public Cache2M2S(int MAX_CAPACITY) {
        MAX_CAPACITY = Math.max(MAX_CAPACITY, 10000);
        PROTECTION_SIZE = (int) (MAX_CAPACITY * 0.8);
        PROBATION_SIZE = (int) (MAX_CAPACITY * 0.19);
        WINDOW_SIZE = (int) (MAX_CAPACITY * 0.1);
        this.MAX_SIZE = PROBATION_SIZE + PROTECTION_SIZE + WINDOW_SIZE;
        this.buffer = new Buffer<>(this);
        this.cache = new ConcurrentHashMap<>(MAX_SIZE);
        this.protectedLRU = new Queue<>(PROTECTION_SIZE);
        this.probationLRU = new Queue<>(PROBATION_SIZE);
        this.windowLRU = new Queue<>(WINDOW_SIZE);
        this.sketch = new Sketch(MAX_SIZE * 4);
    }

    /**
     * Associates the specified value with the specified key in this cache.
     * If the cache previously contained a mapping for the key, the old value is
     * replaced by the specified value.
     *
     * @param key   key with which the specified value is to be associated
     * @param value value to be associated with the specified key
     */
    public void put(K key, V value) {
        Node<K, V> node = cache.get(key);
        if (node != null) {
            node.value = value;
            nudge(node);
            return;
        }

        Node<K, V> newNode = new Node<>(key, value);
        Node<K, V> existingNode = cache.putIfAbsent(key, newNode);

        if (existingNode != null) {
            existingNode.value = value;
            nudge(existingNode);
            return;
        }

        nudge(newNode);
    }

    /**
     * Returns the value to which the specified key is mapped, or {@code null} if this cache
     * contains no mapping for the key.
     *
     * @param key the key whose associated value is to be returned
     * @return the value to which the specified key is mapped, or {@code null} if no mapping
     */
    public V get(K key) {
        Node<K, V> node = cache.get(key);
        if (node != null) {
            nudge(node);
            return node.value;
        }
        return null;
    }

    /**
     * Removes the mapping for a key from this cache if it is present.
     *
     .
     * @param key key whose mapping is to be removed from the cache
     */
    public void remove(K key) {
        Node<K, V> node = cache.remove(key);
        if (node != null) {
            node.isDeleted = true;
            nudge(node);
        }
    }

    /**
     * Returns the number of key-value mappings in this cache.
     *
     * @return the number of key-value mappings in this cache
     */
    public int size() {
        return cache.size();
    }

    /**
     * Evicts a node from the cache when the window queue is full.
     * The eviction logic decides whether to discard the incoming node or a node
     * from the probation area based on their estimated access frequency.
     */
    protected void evict(){
        if(windowLRU.full()){
            Node<K, V> evicted = windowLRU.back();
            if(evicted.queueType == -2) System.err.println("critical error");
            windowLRU.unlink(evicted);
            evicted.queueType = LOC_UNLINKED;
            if(evicted.isDeleted) {return;}

            if (!probationLRU.full()) {
                probationLRU.link(evicted);
                evicted.queueType = LOC_PROBATION;
                return;
            }

            Node<K, V> victim = probationLRU.back();
            if (victim.isDeleted) {
                probationLRU.unlink(victim);
                probationLRU.link(evicted);
                victim.queueType = LOC_UNLINKED;
                evicted.queueType = LOC_PROBATION;
                return;
            }

            if(sketch.frequency(victim.hash) <= sketch.frequency(evicted.hash)){
                probationLRU.unlink(victim);
                cache.remove(victim.key);
                probationLRU.link(evicted);
                victim.queueType = LOC_UNLINKED;
                evicted.queueType = LOC_PROBATION;
                return;
            }
            cache.remove(evicted.key);
        }
    }

    /**
     * Processes a node from the buffer. This method is called by the buffer's drain
     * mechanism and handles the core logic of moving nodes between queues, promoting,
     * demoting, and handling deletions.
     *
     * @param node The node to process.
     */
    protected void processNode(Node<K, V> node){
        if (node.isDeleted) {
            switch (node.queueType) {
                case LOC_WINDOW: windowLRU.unlink(node); break;
                case LOC_PROBATION: probationLRU.unlink(node); break;
                case LOC_PROTECTED: protectedLRU.unlink(node); break;
            }
            node.queueType = LOC_UNLINKED;
            return;
        }
        if(node.queueType == LOC_NEW) {
            windowLRU.link(node);
            node.queueType = LOC_WINDOW;
            evict();
        }
        else if(node.queueType == LOC_WINDOW){
            if(windowLRU.head.next == node) return;
            windowLRU.unlink(node);
            windowLRU.link(node);
        }

        else if(node.queueType == LOC_PROBATION) {
            probationLRU.unlink(node);
            protectedLRU.link(node);
            node.queueType = LOC_PROTECTED;
            if(!protectedLRU.full()) return;
            Node<K, V> demoted = protectedLRU.back();
            protectedLRU.unlink(demoted);
            if(demoted.isDeleted){
                demoted.queueType = LOC_UNLINKED;
                return;
            }
            probationLRU.link(demoted);
            demoted.queueType = LOC_PROBATION;

        }

        else if(node.queueType == LOC_PROTECTED){
            if(protectedLRU.head.next == node) return;
            protectedLRU.unlink(node);
            protectedLRU.link(node);
        }
    }

    /**
     * "Nudges" a node to signify it has been accessed. This involves incrementing its
     * frequency in the sketch and adding it to the buffer for processing.
     *
     * @param node The node that was accessed.
     */
    private void nudge(Node<K, V> node) {
        batch.get().increment(node.hash, sketch);
        buffer.offer(node);
    }

}
