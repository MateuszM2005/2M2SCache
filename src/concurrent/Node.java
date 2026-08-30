package concurrent;

/**
 * 2M2S doc
 * A node in a doubly linked list.
 * @param <K> the type of keys maintained by this cache
 * @param <V> the type of mapped values
 */
public class Node<K, V> {
    /**
     * Queue sentinel. Head and tail guards carry this and are never cache entries.
     */
    static final byte LOC_SENTINEL = -2;
    /**
     * Not currently a member of any queue.
     */
    static final byte LOC_UNLINKED = -1;
    /**
     * Admitted to the map but not yet drained into a queue.
     */
    static final byte LOC_NEW = 0;
    static final byte LOC_WINDOW = 1;
    static final byte LOC_PROBATION = 2;
    static final byte LOC_PROTECTED = 3;

    /**
     * The key of the node.
     */
    final K key;
    /**
     * The value of the node.
     */
    V value;
    /**
     * The previous and next nodes in the doubly linked list.
     */
    Node<K, V> prev, next;
    /**
     * The hash code of the key.
     */
    final int hash;
    /**
     * Which queue currently holds this node, as one of the {@code LOC_} constants.
     */
    byte queueType;
    /**
     * Whether the node is marked as deleted.
     */
    boolean isDeleted;

    /**
     * Constructs a new node with the given key and value.
     * @param key the key
     * @param value the value
     */
    Node(K key, V value) {
        this.key = key;
        this.value = value;
        this.queueType = LOC_NEW;
        this.hash = key.hashCode();
    }

    /**
     * Constructs a new sentinel node.
     */
    Node() {
        this.key = null;
        this.hash = 0;
        this.queueType = LOC_SENTINEL;
    }
}
