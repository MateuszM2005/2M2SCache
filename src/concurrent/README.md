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

3.  **`Buffer<K, V>`**: Cache operations are recorded into two lock-free buffers, then applied to the queues by whichever thread finds a ring half full and takes the shared drain lock. There is no background thread.
    *   **Write buffer**: one ring of 4096 slots. Admissions, deletions, and probation hits (promotions) go here and are never dropped. The ring stays deep because a shallow drain measurably degrades hit rate on a minimum-sized cache.
    *   **Read buffer**: up to four rings of 1024 slots, one cursor per cache line, each thread pinned to a ring. Window and protected hits only refresh recency; if a ring is full the record is discarded rather than making the caller wait.

4.  **`Sketch`**: A Count-Min Sketch data structure is used to estimate the access frequency of items. When an eviction is necessary, the cache consults the sketch to compare the frequency of the incoming item (from the Window) and the least-recently-used item in the Probation area. This allows the cache to make more intelligent decisions and keep "hot" items even if they were accessed only once recently.

5.  **`Batch`**: To reduce contention on the `Sketch` itself, updates from each thread are collected in a `ThreadLocal` `Batch`. Repeated accesses to the same key fold into one entry, so a full batch costs one counter update per *distinct* key rather than one per access, and the sketch's shared sample counter is advanced once per flush instead of once per access.

### How It Works

1.  A `get()` or `put()` operation occurs on a key.
2.  The operation is recorded by "nudging" the corresponding `Node` into the `Buffer`. The access frequency is also noted in the thread-local `Batch`.
3.  The `Buffer` is periodically drained. When a node is processed, its location is updated based on its current state:
    *   **New Node**: Placed in the `windowLRU`. If the window is full, an eviction is triggered.
    *   **Node in Window/Protected**: Moved to the front of its respective LRU queue.
    *   **Node in Probation**: Promoted to the `protectedLRU`. If the protected region is full, a node is demoted from Protected back to Probation.
4.  **Eviction**: During an eviction, the cache compares the frequency of the new item from the window with the victim item from the probation area. The item with the lower frequency is discarded. This prevents popular-but-infrequently-accessed items from being immediately evicted.

### Drain depth is part of the policy

How many nodes a drain processes at a time is not just a throughput knob, it changes hit rate. On a minimum-sized cache (10,000 entries, so a 100-entry window) a read-through workload over a working set of 9,000 keys settles at:

| Slots per ring | Final hit rate | Resident entries |
| --- | --- | --- |
| 256 | 52% | 6,729 |
| 512 | 43% | ~6,000 |
| 1024 | 100% | 9,000 |
| 4096 | 100% | 9,000 |

The same sweep at capacities of 50,000 and 200,000 stays at 100% throughout, so the effect is confined to the smallest cache the constructor allows, where the window is only 100 entries. The mechanism has not been pinned down; what matters practically is that shrinking a ring below roughly 1024 slots is not a free trade.

This is why writes keep a single 4096-slot ring and reads are allowed to drop. Sharding the write ring, or putting promotions on the lossy path, fills only window plus probation (~20%) on a 10,000-entry cache.
