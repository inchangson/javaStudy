package mapstorage.cache.lru;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

class LruCacheTest {
    @Test void readAndReplacementBothChangeEvictionOrder() {
        var cache = new LruCache<String, Integer>(2);
        cache.put("A", 1); cache.put("B", 2);
        var snapshot = cache.keysOldestFirst();
        cache.get("A");
        assertEquals(List.of("B", "A"), cache.keysOldestFirst());
        cache.put("C", 3);
        assertNull(cache.get("B"));
        assertEquals(List.of("A", "C"), cache.keysOldestFirst());
        assertEquals(1, cache.put("A", 4));
        assertEquals(List.of("C", "A"), cache.keysOldestFirst());
        assertEquals(List.of("A", "B"), snapshot);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add("D"));
    }
    @Test void accessOrderGetInvalidatesAnExistingIterator() {
        var map = new LinkedHashMap<String, Integer>(16, .75f, true);
        map.put("A", 1); map.put("B", 2);
        var iterator = map.keySet().iterator();
        map.get("A");
        assertThrows(ConcurrentModificationException.class, iterator::next);
    }
    @Test @Timeout(15) void concurrentAccessKeepsCapacityAndUniqueKeys() throws Exception {
        var cache = new LruCache<Integer, Integer>(8);
        try (var workers = Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<Future<?>>();
            for (int t = 0; t < 4; t++) {
                int offset = t * 1000;
                futures.add(workers.submit(() -> {
                    for (int i = 0; i < 1000; i++) {
                        cache.put(offset + i, i);
                        cache.get(offset + i);
                        var keys = cache.keysOldestFirst();
                        assertTrue(keys.size() <= 8);
                        assertEquals(keys.size(), new HashSet<>(keys).size());
                    }
                }));
            }
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        }
        assertEquals(8, cache.size());
    }
    @Test void rejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new LruCache<>(0));
    }
}
