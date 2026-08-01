package mapstorage.concurrency;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class CounterDemoTest {
    @Test void individuallySafeCallsStillLoseAnIncrement() throws Exception {
        assertEquals(1, CounterDemo.lostUpdate(Collections.synchronizedMap(new HashMap<>())));
        assertEquals(1, CounterDemo.lostUpdate(new ConcurrentHashMap<>()));
    }
    @Test void lockAndComputePreserveAllIncrements() throws Exception {
        assertEquals(2000, CounterDemo.incrementSafely(Collections.synchronizedMap(new HashMap<>()), true));
        assertEquals(2000, CounterDemo.incrementSafely(new ConcurrentHashMap<>(), false));
    }
    @Test void twoComputeCallsExposeIntermediateBalance() throws Exception {
        var balances = new ConcurrentHashMap<>(Map.of("A", 1000, "B", 1000));
        var debited = new CountDownLatch(1);
        var observed = new CountDownLatch(1);
        try (var workers = Executors.newSingleThreadExecutor()) {
            var transfer = workers.submit(() -> {
                balances.compute("A", (key, value) -> value - 100);
                debited.countDown();
                try {
                    if (!observed.await(5, TimeUnit.SECONDS)) throw new AssertionError("reader timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                balances.compute("B", (key, value) -> value + 100);
            });
            try {
                assertTrue(debited.await(5, TimeUnit.SECONDS));
                assertEquals(1900, balances.get("A") + balances.get("B"));
            } finally { observed.countDown(); }
            transfer.get(5, TimeUnit.SECONDS);
        }
        assertEquals(2000, balances.get("A") + balances.get("B"));
    }
}
