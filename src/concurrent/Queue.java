package concurrent;

/**
 * 2M2S doc
 * A simple, non-thread-safe, doubly-linked list implementation of a queue.
 * This class is used to implement the LRU (Least Recently Used) policy for the
 * different regions of the cache (window, probation, protected).
 *
 * It is not thread-safe and assumes that all access is synchronized externally,
 * for example, by holding a lock.
 *
 * @param <K> the type of keys maintained by the cache
 * @param <V> the type of mapped values
 */
class Queue<K, V> {
    /**
     * The head sentinel of the queue, representing the most recently used end.
     */
    public final Node<K, V> head = new Node<>();
    /**
     * The tail sentinel of the queue, representing the least recently used end.
     */
    public final Node<K, V> tail = new Node<>();
    private final int capacity;
    private int size;

    /**
     * Constructs a new queue with a given capacity.
     * @param capacity The maximum number of elements in the queue.
     */
    Queue(int capacity) {
        this.capacity = capacity;
        this.size = 0;

        head.next = tail;
        head.isDeleted = false;
        head.queueType = Node.LOC_SENTINEL;

        tail.prev = head;
        tail.isDeleted = false;
        tail.queueType = Node.LOC_SENTINEL;
    }

    /**
     * Removes a node from the queue.
     * @param node The node to remove.
     */
    public void unlink(Node<K, V> node) {
        size--;
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    /**
     * Adds a node to the front of the queue (most recently used).
     * @param node The node to add.
     */
    public void link(Node<K, V> node) {
        size++;
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }

    /**
     * Returns the least recently used node in the queue (the one before the tail).
     * @return The least recently used node.
     */
    public Node<K, V> back() {
        return tail.prev;
    }

    /**
     * Checks if the queue has reached its capacity.
     * @return {@code true} if the queue is full, {@code false} otherwise.
     */
    public boolean full() {
        return size >= capacity;
    }
}
