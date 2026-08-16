package poolstudy.commons;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.NoSuchElementException;
import static org.junit.jupiter.api.Assertions.*;

class CommonsPoolTest {
    @Test void resetsValidatesReplacesAndDestroys() throws Exception {
        var factory = new CommonsPoolDemo.Factory();
        CommonsPoolDemo.Resource replacement;
        try (var pool = CommonsPoolDemo.newPool(factory)) {
            var first = pool.borrowObject();
            first.state = "dirty";
            pool.returnObject(first);
            var same = pool.borrowObject();
            assertSame(first, same);
            assertEquals("", same.state);
            pool.returnObject(same);
            same.valid = false;
            replacement = pool.borrowObject();
            assertNotSame(first, replacement);
            assertTrue(first.destroyed);
            pool.returnObject(replacement);
        }
        assertTrue(replacement.destroyed);
        assertEquals(List.of("create 1", "activate 1", "validate 1", "passivate 1",
                "activate 1", "validate 1", "passivate 1", "activate 1", "validate 1",
                "destroy 1", "create 2", "activate 2", "validate 2", "passivate 2", "destroy 2"), factory.events);
    }

    @Test void exhaustionAndDuplicateReturnDoNotIncreaseCapacity() throws Exception {
        try (var pool = CommonsPoolDemo.newPool(new CommonsPoolDemo.Factory())) {
            var first = pool.borrowObject();
            try {
                assertThrows(NoSuchElementException.class, pool::borrowObject);
                assertEquals(1, pool.getNumActive());
            } finally { pool.returnObject(first); }
            assertThrows(IllegalStateException.class, () -> pool.returnObject(first));
            assertEquals(1, pool.getNumIdle());
        }
    }

    @Test void returnAfterShutdownDestroysBorrowedResource() throws Exception {
        var pool = CommonsPoolDemo.newPool(new CommonsPoolDemo.Factory());
        var first = pool.borrowObject();
        pool.close();
        assertFalse(first.destroyed);
        pool.returnObject(first);
        assertTrue(first.destroyed);
        assertThrows(IllegalStateException.class, pool::borrowObject);
    }
}
