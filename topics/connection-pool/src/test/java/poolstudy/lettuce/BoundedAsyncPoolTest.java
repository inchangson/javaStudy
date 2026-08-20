package poolstudy.lettuce;

import io.lettuce.core.support.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.NoSuchElementException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class BoundedAsyncPoolTest {
    static final class Factory implements AsyncObjectFactory<Object> {
        final AtomicInteger destroyed = new AtomicInteger();
        CompletableFuture<Void> destruction = CompletableFuture.completedFuture(null);
        CompletableFuture<Object> creation = CompletableFuture.completedFuture(new Object());
        @Override public CompletableFuture<Object> create() { return creation; }
        @Override public CompletableFuture<Void> destroy(Object object) {
            destroyed.incrementAndGet();
            return destruction;
        }
        @Override public CompletableFuture<Boolean> validate(Object object) {
            return CompletableFuture.completedFuture(true);
        }
    }
    static BoundedAsyncPool<Object> pool(Factory factory) {
        return BoundedAsyncPool.create(factory, BoundedPoolConfig.builder()
                .maxTotal(1).maxIdle(1).minIdle(0).build()).toCompletableFuture().join();
    }

    @Test void exhaustionFailsWithoutAWaitQueue() {
        var factory = new Factory();
        try (var pool = pool(factory)) {
            var held = pool.acquire().join();
            var rejected = pool.acquire();
            assertTrue(rejected.isCompletedExceptionally());
            assertInstanceOf(NoSuchElementException.class,
                    assertThrows(CompletionException.class, rejected::join).getCause());
            pool.release(held).join();
            var reused = pool.acquire().join();
            assertSame(held, reused);
            pool.release(reused).join();
        }
        assertEquals(1, factory.destroyed.get());
    }

    @Test void pendingCreationCountsAgainstCapacityAndCancellationReturnsLateObject() {
        var factory = new Factory();
        factory.creation = new CompletableFuture<>();
        try (var pool = pool(factory)) {
            var pending = pool.acquire();
            assertEquals(1, pool.getCreationInProgress());
            assertTrue(pool.acquire().isCompletedExceptionally());
            assertTrue(pending.cancel(false));
            factory.creation.complete(new Object());
            assertEquals(0, pool.getCreationInProgress());
            assertEquals(1, pool.getIdle());
        }
        assertEquals(1, factory.destroyed.get());
    }

    @Test void failedCreationReleasesReservation() {
        var factory = new Factory();
        factory.creation = CompletableFuture.failedFuture(new IllegalStateException("connect failed"));
        try (var pool = pool(factory)) {
            assertThrows(CompletionException.class, () -> pool.acquire().join());
            assertEquals(0, pool.getCreationInProgress());
            factory.creation = CompletableFuture.completedFuture(new Object());
            pool.release(pool.acquire().join()).join();
        }
    }

    @Test void operationFailuresStillReturnObject() {
        try (var pool = pool(new Factory())) {
            assertThrows(CompletionException.class, () -> AsyncLease.use(pool, object -> {
                throw new IllegalArgumentException("before future");
            }).toCompletableFuture().join());
            assertEquals(1, pool.getIdle());
            assertThrows(CompletionException.class, () -> AsyncLease.use(pool,
                    object -> CompletableFuture.failedFuture(new IllegalStateException("command failed")))
                    .toCompletableFuture().join());
            assertEquals(1, pool.getIdle());
        }
    }

    @Test void releaseCompletionIsPartOfOperationCompletion() {
        var factory = new Factory();
        try (var pool = pool(factory)) {
            var command = new CompletableFuture<String>();
            var result = AsyncLease.use(pool, object -> command).toCompletableFuture();
            assertFalse(result.isDone());
            assertEquals(0, pool.getIdle());
            command.complete("done");
            assertEquals("done", result.join());
            assertEquals(1, pool.getIdle());
        }
    }
    @Test void delayedReleaseAndItsFailureAreNotLost() {
        var factory = new Factory();
        factory.destruction = new CompletableFuture<>();
        try (var pool = BoundedAsyncPool.create(factory, BoundedPoolConfig.builder()
                .maxTotal(1).maxIdle(0).minIdle(0).build()).toCompletableFuture().join()) {
            var result = AsyncLease.use(pool, object -> CompletableFuture.completedFuture("work done"))
                    .toCompletableFuture();
            assertFalse(result.isDone(), "must wait for destruction on release");
            var releaseFailure = new IllegalStateException("destroy failed");
            factory.destruction.completeExceptionally(releaseFailure);
            assertSame(releaseFailure, assertThrows(CompletionException.class, result::join).getCause());

            var workFailure = new IllegalArgumentException("work failed");
            var both = AsyncLease.use(pool, object -> CompletableFuture.failedFuture(workFailure))
                    .toCompletableFuture();
            assertSame(workFailure, assertThrows(CompletionException.class, both::join).getCause());
            assertArrayEquals(new Throwable[]{releaseFailure}, workFailure.getSuppressed());
        }
    }

    @Test void cancelledCallerStillReturnsAnObjectCreatedLater() {
        var factory = new Factory();
        factory.creation = new CompletableFuture<>();
        try (var pool = pool(factory)) {
            var caller = AsyncLease.use(pool, object -> {
                fail("action must not start after observed cancellation");
                return CompletableFuture.completedFuture("unexpected");
            }).toCompletableFuture();
            caller.cancel(false);
            factory.creation.complete(new Object());
            assertTrue(caller.isCancelled());
            assertEquals(1, pool.getIdle());
        }
        assertEquals(1, factory.destroyed.get());
    }

}
