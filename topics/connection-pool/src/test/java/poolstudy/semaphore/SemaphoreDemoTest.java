package poolstudy.semaphore;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SemaphoreDemoTest {
    @Test void failureReturnsOnlyAnAcquiredPermit() throws Exception {
        var gate = new Semaphore(1);
        assertThrows(IllegalStateException.class, () -> SemaphoreDemo.withPermit(gate,
                Duration.ofSeconds(1), () -> { throw new IllegalStateException("work failed"); }));
        assertEquals(1, gate.availablePermits());
        gate.acquire();
        assertThrows(TimeoutException.class, () -> SemaphoreDemo.withPermit(gate,
                Duration.ZERO, () -> fail("must not run")));
        assertEquals(0, gate.availablePermits());
    }

    @Test void interruptedWaitDoesNotInventPermit() throws Exception {
        var gate = new Semaphore(0);
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> {
                Thread.currentThread().interrupt();
                assertThrows(InterruptedException.class, () -> SemaphoreDemo.withPermit(gate,
                        Duration.ofSeconds(1), () -> fail("must not run")));
                assertFalse(Thread.currentThread().isInterrupted());
            }).get(2, TimeUnit.SECONDS);
        }
        assertEquals(0, gate.availablePermits());
    }
}
