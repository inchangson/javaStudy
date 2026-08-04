package mapstorage.cache.caffeine;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CaffeineDemoTest {
    @Test void dataIsVisibleBeforePolicyMaintenance() {
        var executor = new CaffeineDemo.ManualExecutor();
        var cache = Caffeine.newBuilder().maximumSize(2).executor(executor)
                .<String, Integer>build();
        cache.put("A", 1); cache.put("B", 2); cache.put("C", 3);
        assertEquals(1, cache.getIfPresent("A"));
        assertEquals(3, cache.estimatedSize()); // version-specific controlled observation
        assertTrue(executor.pendingTasks() > 0);
        cache.cleanUp(); executor.runPending();
        assertEquals(2, cache.estimatedSize());
        assertEquals(2, cache.asMap().size());
    }
    @Test void expiredValueIsInvisibleBeforePhysicalRemoval() {
        var clock = new AtomicLong();
        var executor = new CaffeineDemo.ManualExecutor();
        var cache = Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(5))
                .ticker(clock::get).executor(executor).<String, Integer>build();
        cache.put("A", 1); cache.cleanUp(); executor.runPending();
        clock.set(Duration.ofSeconds(4).toNanos());
        assertEquals(1, cache.getIfPresent("A"));
        clock.set(Duration.ofSeconds(5).toNanos());
        assertEquals(1, cache.estimatedSize());
        assertNull(cache.getIfPresent("A"));
        cache.cleanUp(); executor.runPending();
        assertEquals(0, cache.estimatedSize());
    }
    @Test void getLoadsOnceUntilInvalidation() {
        var calls = new AtomicInteger();
        var cache = Caffeine.newBuilder().maximumSize(10).<String, Integer>build();
        assertEquals(1, cache.get("A", key -> calls.incrementAndGet()));
        assertEquals(1, cache.get("A", key -> calls.incrementAndGet()));
        cache.invalidate("A");
        assertEquals(2, cache.get("A", key -> calls.incrementAndGet()));
        assertEquals(2, calls.get());
    }
}
