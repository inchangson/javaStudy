package mapstorage.hashmap;

import java.util.HashMap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HashMapDemoTest {
    @Test void collisionDoesNotMeanEquality() {
        var map = new HashMap<HashMapDemo.HashCodeCollisionKey, String>();
        map.put(new HashMapDemo.HashCodeCollisionKey(1), "a");
        map.put(new HashMapDemo.HashCodeCollisionKey(2), "b");
        assertEquals("a", map.put(new HashMapDemo.HashCodeCollisionKey(1), "c"));
        assertEquals(2, map.size());
        assertEquals("b", map.get(new HashMapDemo.HashCodeCollisionKey(2)));
    }

    @Test void equalKeysWithDifferentHashesSkipEqualityEvenInSameBucket() throws Exception {
        var first = new HashMapDemo.EqualsCollisionKey(1, 1);
        var second = new HashMapDemo.EqualsCollisionKey(1, 17);
        assertEquals(first, second); // This key intentionally breaks the contract.
        assertNotEquals(first.hashCode(), second.hashCode());
        var map = new HashMap<HashMapDemo.EqualsCollisionKey, String>();
        map.put(first, "first");
        assertNull(map.put(second, "second"));
        assertEquals(16, table(map).length);
        assertEquals((16 - 1) & first.hashCode(), (16 - 1) & second.hashCode());
        assertEquals(2, map.size()); // OpenJDK observation, not a valid-key API guarantee.
        assertEquals("first", map.get(new HashMapDemo.EqualsCollisionKey(1, 1)));
        assertEquals("second", map.get(new HashMapDemo.EqualsCollisionKey(1, 17)));
    }

    @Test void sameHashAndEqualKeyReplaceValueAndRetainOriginalKeyReference() {
        var first = new HashMapDemo.HashCodeEqualsCollisionKey(1);
        var second = new HashMapDemo.HashCodeEqualsCollisionKey(1);
        assertNotSame(first, second);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        var map = new HashMap<HashMapDemo.HashCodeEqualsCollisionKey, String>();
        map.put(first, "first");
        assertEquals("first", map.put(second, "updated"));
        assertEquals(1, map.size());
        assertEquals("updated", map.get(second));
        assertSame(first, map.keySet().iterator().next());
    }

    @Test void changingHashMakesEvenSameInstanceUnfindable() {
        var key = new HashMapDemo.MutableKey(1);
        var map = new HashMap<HashMapDemo.MutableKey, String>();
        map.put(key, "value");
        key.changeId(2);
        assertNull(map.get(key));
        assertEquals(1, map.size());
        key.changeId(1);
        assertEquals("value", map.get(key));
    }

    @Test void thirteenthEntryDoublesDefaultTable() throws Exception {
        var map = new HashMap<Integer, Integer>();
        assertNull(table(map)); // allocation is lazy
        for (int i = 0; i < 12; i++) map.put(i, i);
        assertEquals(16, table(map).length);
        map.put(12, 12);
        assertEquals(32, table(map).length);
        for (int i = 0; i < 13; i++) assertEquals(i, map.get(i));
    }

    @Test void longCollisionChainTreeifiesWhenTableIsLargeEnough() throws Exception {
        var map = new HashMap<HashMapDemo.HashCodeCollisionKey, Integer>(64);
        for (int i = 0; i < 9; i++) map.put(new HashMapDemo.HashCodeCollisionKey(i), i);
        assertEquals("java.util.HashMap$TreeNode", table(map)[7].getClass().getName());
        for (int i = 0; i < 9; i++) assertEquals(i, map.get(new HashMapDemo.HashCodeCollisionKey(i)));
    }

    private Object[] table(HashMap<?, ?> map) throws Exception {
        var field = HashMap.class.getDeclaredField("table");
        field.setAccessible(true);
        return (Object[]) field.get(map);
    }
}
