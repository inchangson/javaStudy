package poolstudy.tiny;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class TinyPoolTest {
    @Test void leaseReturnsExactlyOnceAndTimesOutWhenFull() throws Exception {
        Object object = new Object();
        try (var pool = new TinyPool<>(List.of(object))) {
            var lease = pool.borrow(Duration.ZERO);
            assertThrows(TimeoutException.class, () -> pool.borrow(Duration.ofMillis(10)));
            lease.close();
            lease.close();
            assertThrows(IllegalStateException.class, lease::get);
            try (var again = pool.borrow(Duration.ZERO)) {
                assertSame(object, again.get());
                assertThrows(TimeoutException.class, () -> pool.borrow(Duration.ZERO));
            }
        }
    }

    @Test void closedPoolRejectsBorrowsAndAcceptsOutstandingReturn() throws Exception {
        var pool = new TinyPool<>(List.of(new Object()));
        var lease = pool.borrow(Duration.ZERO);
        pool.close();
        lease.close();
        assertThrows(IllegalStateException.class, () -> pool.borrow(Duration.ZERO));
    }

    @Test void rejectsAliasedObjects() {
        Object object = new Object();
        assertThrows(IllegalArgumentException.class, () -> new TinyPool<>(List.of(object, object)));
    }

    @Test void neverLendsSameIdentityToTwoWorkers() throws Exception {
        var inUse = ConcurrentHashMap.newKeySet();
        try (var pool = new TinyPool<>(List.of(new Object(), new Object()));
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 24; i++) futures.add(executor.submit(() -> {
                start.await();
                for (int j = 0; j < 100; j++) {
                    try (var lease = pool.borrow(Duration.ofSeconds(3))) {
                        assertTrue(inUse.add(lease.get()), "duplicate concurrent lease");
                        Thread.yield();
                        assertTrue(inUse.remove(lease.get()));
                    }
                }
                return null;
            }));
            start.countDown();
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
        }
        assertTrue(inUse.isEmpty());
    }
}
