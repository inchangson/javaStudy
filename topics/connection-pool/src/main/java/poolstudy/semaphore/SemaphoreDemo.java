package poolstudy.semaphore;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** A permit limits concurrency; it does not identify an object or its owner. */
public final class SemaphoreDemo {
    public static void withPermit(Semaphore gate, Duration timeout, Runnable action)
            throws InterruptedException, TimeoutException {
        if (!gate.tryAcquire(timeout.toNanos(), TimeUnit.NANOSECONDS)) {
            throw new TimeoutException("permit exhausted");
        }
        try {
            action.run();
        } finally {
            gate.release();
        }
    }

    public static void main(String[] args) throws Exception {
        var gate = new Semaphore(1, true);
        withPermit(gate, Duration.ofSeconds(1), () ->
                System.out.println("inside: available=" + gate.availablePermits()));
        System.out.println("returned: available=" + gate.availablePermits());
        gate.release(); // Deliberately wrong: Semaphore cannot detect a foreign return.
        System.out.println("extra release: available=" + gate.availablePermits());
    }
}
