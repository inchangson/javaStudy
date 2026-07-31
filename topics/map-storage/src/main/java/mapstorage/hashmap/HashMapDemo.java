package mapstorage.hashmap;

import java.util.HashMap;

public final class HashMapDemo {
    public static final class HashCodeCollisionKey {
        private final int id;

        public HashCodeCollisionKey(int id) {
            this.id = id;
        }

        @Override
        public int hashCode() {
            return 7;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof HashCodeCollisionKey key && id == key.id;
        }
    }

    // Deliberately violates equals/hashCode contract for the comparison experiment.
    public static final class EqualsCollisionKey {
        private final int id;
        private final int hash;

        public EqualsCollisionKey(int id, int hash) {
            this.id = id;
            this.hash = hash;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof EqualsCollisionKey key && id == key.id;
        }
    }

    public static final class HashCodeEqualsCollisionKey {
        private final int id;

        public HashCodeEqualsCollisionKey(int id) {
            this.id = id;
        }

        @Override
        public int hashCode() {
            return id;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof HashCodeEqualsCollisionKey key && id == key.id;
        }
    }

    public static final class MutableKey {
        private int id;

        public MutableKey(int id) {
            this.id = id;
        }

        public void changeId(int id) {
            this.id = id;
        }

        @Override
        public int hashCode() {
            return id;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof MutableKey key && id == key.id;
        }
    }

    public static void main(String[] args) {
        hashCodeCollision();
        equalsCollision();
        hashCodeEqualsCollision();
        mutableKey();
    }

    private static void hashCodeCollision() {
        var map = new HashMap<HashCodeCollisionKey, String>();
        map.put(new HashCodeCollisionKey(1), "first");
        System.out.println("hash only: previous = " + map.put(new HashCodeCollisionKey(2), "second"));
        System.out.println("hash only: size = " + map.size());
        System.out.println("hash only: key 1 = " + map.get(new HashCodeCollisionKey(1)));
        System.out.println("hash only: key 2 = " + map.get(new HashCodeCollisionKey(2)));
    }

    private static void equalsCollision() {
        // Same id, different hashes: deliberately broken key contract.
        // 1 and 17 also select the same bucket when table length is 16.
        var map = new HashMap<EqualsCollisionKey, String>();
        map.put(new EqualsCollisionKey(1, 1), "first");
        System.out.println("equals only (broken contract): previous = "
                + map.put(new EqualsCollisionKey(1, 17), "second"));
        System.out.println("equals only (broken contract): size = " + map.size());
        System.out.println("equals only (broken contract): hash 1 = " + map.get(new EqualsCollisionKey(1, 1)));
        System.out.println("equals only (broken contract): hash 17 = " + map.get(new EqualsCollisionKey(1, 17)));
    }

    private static void hashCodeEqualsCollision() {
        var map = new HashMap<HashCodeEqualsCollisionKey, String>();
        map.put(new HashCodeEqualsCollisionKey(1), "first");
        System.out.println("hash + equals: previous = " + map.put(new HashCodeEqualsCollisionKey(1), "updated"));
        System.out.println("hash + equals: size = " + map.size());
        System.out.println("hash + equals: key 1 = " + map.get(new HashCodeEqualsCollisionKey(1)));
    }

    private static void mutableKey() {
        var key = new MutableKey(1);
        var map = new HashMap<MutableKey, String>();
        map.put(key, "stored");
        key.changeId(2);
        System.out.println("mutable: get = " + map.get(key));
        System.out.println("mutable: size = " + map.size());
    }
}
