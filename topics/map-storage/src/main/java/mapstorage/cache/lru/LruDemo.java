package mapstorage.cache.lru;

public final class LruDemo {
    public static void main(String[] args) {
        var cache = new LruCache<String, Integer>(2);
        cache.put("A", 1);
        cache.put("B", 2);
        System.out.println(cache.keysOldestFirst());
        cache.get("A");
        System.out.println(cache.keysOldestFirst());
        cache.put("C", 3);
        System.out.println(cache.keysOldestFirst());
    }
}
