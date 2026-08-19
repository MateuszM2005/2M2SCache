package concurrent;

/**
 * 2M2S doc
 * A node in a doubly linked list.
 * @param <K> the type of keys maintained by this cache
 * @param <V> the type of mapped values
 */
public class Node<K, V> {
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
    int hash;
    /**
     * The type of queue the node belongs to.
     * 0 for probation, 1 for protected.
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
        this.queueType = 0;
        this.hash = key.hashCode();
    }

    /**
     * Constructs a new sentinel node.
     */
    Node() {
        this.key = null;
        this.queueType = -1;
    }
}
