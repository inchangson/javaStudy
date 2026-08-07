package mapstorage.storage.atomic;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class AtomicBalancesTest {
    @Test void failureAfterDebitRestoresBothKeys() {
        var initial = Map.of("A", 1000L, "B", 1000L);
        var store = new AtomicBalances(initial);
        assertThrows(IllegalStateException.class, () -> store.transfer("A", "B", 100,
                () -> { throw new IllegalStateException("after debit"); }));
        assertEquals(initial, store.snapshot());
        store.transfer("A", "B", 100);
        assertEquals(Map.of("A", 900L, "B", 1100L), store.snapshot());
    }
    @Test void validationAndOverflowDoNotChangeState() {
        var store = new AtomicBalances(Map.of("A", 10L, "B", Long.MAX_VALUE));
        var before = store.snapshot();
        assertThrows(ArithmeticException.class, () -> store.transfer("A", "B", 1));
        assertThrows(IllegalArgumentException.class, () -> store.transfer("A", "B", 11));
        assertThrows(IllegalArgumentException.class, () -> store.transfer("A", "A", 1));
        assertThrows(IllegalArgumentException.class, () -> store.transfer("A", "C", 1));
        assertThrows(IllegalArgumentException.class, () -> store.transfer("A", "B", 0));
        assertEquals(before, store.snapshot());
        assertThrows(UnsupportedOperationException.class, () -> before.put("A", 0L));
    }
    @Test void readerBlocksDuringIntermediateState() throws Exception {
        var store = new AtomicBalances(Map.of("A", 1000L, "B", 1000L));
        var debited = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var readerResult = new CompletableFuture<Map<String, Long>>();
        Thread reader = new Thread(() -> {
            try { readerResult.complete(store.snapshot()); }
            catch (Throwable e) { readerResult.completeExceptionally(e); }
        });
        try (var worker = Executors.newSingleThreadExecutor()) {
            var transfer = worker.submit(() -> store.transfer("A", "B", 100, () -> {
                debited.countDown();
                try {
                    if (!finish.await(5, TimeUnit.SECONDS)) throw new AssertionError("release timed out");
                } catch (InterruptedException e) { throw new AssertionError(e); }
            }));
            try {
                assertTrue(debited.await(5, TimeUnit.SECONDS));
                reader.start();
                assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                    while (reader.getState() != Thread.State.BLOCKED) {
                        if (Thread.currentThread().isInterrupted()) throw new AssertionError("interrupted");
                        Thread.onSpinWait();
                    }
                });
                assertFalse(readerResult.isDone());
            } finally { finish.countDown(); }
            transfer.get(5, TimeUnit.SECONDS);
            reader.join(5000);
        }
        assertEquals(Map.of("A", 900L, "B", 1100L), readerResult.get(5, TimeUnit.SECONDS));
    }
}
