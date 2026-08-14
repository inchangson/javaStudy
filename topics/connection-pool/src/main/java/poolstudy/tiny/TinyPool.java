package poolstudy.tiny;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fixed, pre-populated object pool. Does not own/close physical resources. */
public final class TinyPool<T> implements AutoCloseable {
    private final ArrayDeque<T> idle;
    private final Semaphore permits;
    private boolean closed;

    public TinyPool(Collection<? extends T> objects) {
        if (objects.isEmpty()) throw new IllegalArgumentException("empty pool");
        var identities = Collections.newSetFromMap(new IdentityHashMap<T, Boolean>());
        for (T object : objects) {
            if (!identities.add(Objects.requireNonNull(object))) {
                throw new IllegalArgumentException("duplicate object identity");
            }
        }
        idle = new ArrayDeque<>(objects);
        permits = new Semaphore(idle.size(), true);
    }

    public Lease<T> borrow(Duration timeout) throws InterruptedException, TimeoutException {
        if (timeout.isNegative()) throw new IllegalArgumentException("negative timeout");
        synchronized (this) { ensureOpen(); }
        if (!permits.tryAcquire(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
            throw new TimeoutException("pool exhausted");
        }
        synchronized (this) {
            if (closed) {
                permits.release();
                throw new IllegalStateException("pool closed");
            }
            return new Lease<>(this, idle.removeFirst());
        }
    }

    private synchronized void giveBack(T object) {
        if (!closed) idle.addLast(object); // Publish the object before the permit.
        permits.release();
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("pool closed");
    }

    @Override public synchronized void close() {
        closed = true;
        idle.clear();
    }

    public static final class Lease<T> implements AutoCloseable {
        private final TinyPool<T> owner;
        private final T object;
        private final AtomicBoolean returned = new AtomicBoolean();
        private Lease(TinyPool<T> owner, T object) { this.owner = owner; this.object = object; }
        public T get() {
            if (returned.get()) throw new IllegalStateException("lease returned");
            return object;
        }
        @Override public void close() {
            if (returned.compareAndSet(false, true)) owner.giveBack(object);
        }
    }
}
