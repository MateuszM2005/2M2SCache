# Concurrent Cache Implementation

This directory contains a high-performance, concurrent cache implementation for Java, developed by team 2M2S. It is designed to minimize lock contention and scale well with multiple threads.

## Public Interface

The cache provides a simple and familiar interface, similar to `java.util.Map`.

*   `public Cache2M2S()`: Constructs a cache with a default maximum capacity.
*   `public Cache2M2S(int MAX_CAPACITY)`: Constructs a cache with a specified maximum capacity.
*   `public void put(K key, V value)`: Associates the specified value with the specified key. If the key already exists, the value is updated.
*   `public V get(K key)`: Retrieves the value associated with the specified key, or `null` if the key is not present. Accessing a key marks it as recently used.
*   `public void remove(K key)`: Removes the mapping for a key from the cache.
*   `public int size()`: Returns the current number of items in the cache.

## Architecture

The cache uses a sophisticated eviction policy inspired by Segmented LRU (SLRU). The policy is designed to offer better hit rates than traditional LRU for various access patterns by separating new entries from frequently accessed ones.

### Main Components

1.  **`Cache2M2S<K, V>`**: The main class that orchestrates the entire cache. It holds the `ConcurrentHashMap` for key-value storage and manages the different memory regions.

2.  **Memory Regions (Queues)**: The cache is divided into three logical regions, each managed by a `Queue<K, V>` (a simple doubly-linked list):
    *   **Window (`windowLRU`)**: A small, temporary space for all new entries. This acts as an initial filter.
    *   **Probation (`probationLRU`)**: The main area for entries. Items move here from the Window queue. Entries in this region are the primary candidates for eviction.
    *   **Protected (`protectedLRU`)**: A larger region for items that have demonstrated frequent access. Items are promoted from Probation to Protected. An item in Protected that is not accessed for a while is demoted back to Probation, giving it a second chance before eviction.

3.  **`Buffer<K, V>`**: To handle high contention from multiple threads trying to update the cache structure simultaneously, all cache operations (gets, puts, removes) are "nudged" into a lock-free buffer. A background mechanism drains this buffer and applies the changes to the queues in a more controlled manner, reducing bottlenecks.

4.  **`Sketch`**: A Count-Min Sketch data structure is used to estimate the access frequency of items. When an eviction is necessary, the cache consults the sketch to compare the frequency of the incoming item (from the Window) and the least-recently-used item in the Probation area. This allows the cache to make more intelligent decisions and keep "hot" items even if they were accessed only once recently.

5.  **`Batch<K>`**: To reduce contention on the `Sketch` itself, updates from each thread are batched locally using a `ThreadLocal` `Batch` object. When the batch is full, it is flushed to the sketch in one go.

### How It Works

1.  A `get()` or `put()` operation occurs on a key.
2.  The operation is recorded by "nudging" the corresponding `Node` into the `Buffer`. The access frequency is also noted in the thread-local `Batch`.
3.  The `Buffer` is periodically drained. When a node is processed, its location is updated based on its current state:
    *   **New Node**: Placed in the `windowLRU`. If the window is full, an eviction is triggered.
    *   **Node in Window/Protected**: Moved to the front of its respective LRU queue.
    *   **Node in Probation**: Promoted to the `protectedLRU`. If the protected region is full, a node is demoted from Protected back to Probation.
4.  **Eviction**: During an eviction, the cache compares the frequency of the new item from the window with the victim item from the probation area. The item with the lower frequency is discarded. This prevents popular-but-infrequently-accessed items from being immediately evicted.
