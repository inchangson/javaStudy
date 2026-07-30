package mapstorage.hashmap;

import java.util.HashMap;

public final class HashMapDemo {
    public record CollisionKey(int id) {
        @Override public int hashCode() { return 7; }
    }

    public static final class MutableKey {
        private int id;
        public MutableKey(int id) { this.id = id; }
        public void changeId(int id) { this.id = id; }
        @Override public int hashCode() { return id; }
        @Override public boolean equals(Object other) {
            return other instanceof MutableKey key && id == key.id;
        }
    }

    public static void main(String[] args) {
        var collisions = new HashMap<CollisionKey, String>();
        collisions.put(new CollisionKey(1), "first");
        collisions.put(new CollisionKey(2), "second");
        System.out.println("replacement = " + collisions.put(new CollisionKey(1), "updated"));
        System.out.println("distinct keys = " + collisions.size());
        var key = new MutableKey(1);
        var map = new HashMap<MutableKey, String>();
        map.put(key, "stored");
        key.changeId(2);
        System.out.println("after key mutation = " + map.get(key));
        System.out.println("entry still exists = " + map.size());
    }
}
