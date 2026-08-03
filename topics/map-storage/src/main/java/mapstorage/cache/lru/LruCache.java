package mapstorage.cache.lru;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A small exact LRU. Every access, including reads, uses the same monitor. */
public final class LruCache<K, V> {
    private final LinkedHashMap<K, V> entries;

    public LruCache(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > capacity;
            }
        };
    }

    public synchronized V get(K key) { return entries.get(key); }
    public synchronized V put(K key, V value) { return entries.put(key, value); }
    public synchronized int size() { return entries.size(); }
    public synchronized List<K> keysOldestFirst() { return List.copyOf(entries.keySet()); }
}
