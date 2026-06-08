package network;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LRUCache — Least Recently Used cache for seen message IDs.
 *
 * Integration Plan §2.1 (Network Engine State Variables):
 *  - LRUCache<MessageID> seenMessages: Stores SHA-256(message) to prevent
 *    infinite routing loops in the gossip network.
 *  - When a message is received, its SHA-256 hash is checked against this cache.
 *    If already seen, the message is instantly dropped (Step 1 of ingressLoop).
 *  - The LRU eviction policy ensures the cache never grows beyond maxSize,
 *    automatically discarding the oldest (least-recently-seen) message IDs
 *    to prevent RAM leaks over long node uptime.
 *
 * Thread Safety:
 *  This class is synchronized via Collections.synchronizedMap() wrapping
 *  because it is accessed by multiple NetworkEngine reader threads concurrently.
 *
 * @param <K> The key type (typically String for hex message IDs).
 */
public class LRUCache<K> {

    private final int maxSize;
    private final Map<K, Long> cache;

    /**
     * Creates an LRU cache with the specified maximum number of entries.
     *
     * @param maxSize Maximum number of message IDs to retain.
     *                When exceeded, the oldest entry is evicted.
     *                Recommended: 50,000 for a production node.
     */
    public LRUCache(int maxSize) {
        this.maxSize = maxSize;
        // LinkedHashMap with accessOrder=true implements LRU eviction.
        // removeEldestEntry() is called after each put() to enforce capacity.
        this.cache = java.util.Collections.synchronizedMap(
            new LinkedHashMap<K, Long>(maxSize, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<K, Long> eldest) {
                    return size() > maxSize;
                }
            }
        );
    }

    /**
     * Checks if a key is already present in the cache.
     * This is called BEFORE add() to implement the "seen messages" check.
     *
     * @param key The message ID to check.
     * @return true if this message has already been seen.
     */
    public boolean contains(K key) {
        return cache.containsKey(key);
    }

    /**
     * Adds a key to the cache, recording the timestamp it was first seen.
     * If the cache is at capacity, the LRU entry is automatically evicted.
     *
     * @param key The message ID to record as seen.
     */
    public void add(K key) {
        cache.put(key, System.currentTimeMillis());
    }

    /**
     * Returns the current number of entries in the cache.
     */
    public int size() {
        return cache.size();
    }

    /**
     * Clears all entries. Called on node restart.
     */
    public void clear() {
        cache.clear();
    }
}
