package poolstudy.lettuce;

import io.lettuce.core.support.AsyncPool;
import java.util.concurrent.*;
import java.util.function.Function;

/** Complete the operation only after release; command and release failures remain visible. */
public final class AsyncLease {
    public static <T, R> CompletionStage<R> use(AsyncPool<T> pool,
            Function<T, ? extends CompletionStage<R>> action) {
        var result = new CompletableFuture<R>();
        // Keep acquisition cleanup independent of cancellation of the caller's result.
        CompletionStage<T> acquisition;
        try { acquisition = pool.acquire(); }
        catch (Throwable failure) { return CompletableFuture.failedFuture(failure); }
        acquisition.whenComplete((object, acquisitionFailure) -> {
            if (acquisitionFailure != null) {
                result.completeExceptionally(acquisitionFailure);
                return;
            }
            if (result.isCancelled()) {
                finish(pool, object, null, null, result);
                return;
            }
            CompletionStage<R> work;
            try { work = java.util.Objects.requireNonNull(action.apply(object)); }
            catch (Throwable failure) { work = CompletableFuture.failedFuture(failure); }
            work.whenComplete((value, failure) -> finish(pool, object, value, failure, result));
        });
        return result;
    }

    private static <T, R> void finish(AsyncPool<T> pool, T object, R value, Throwable failure,
                                    CompletableFuture<R> result) {
        CompletionStage<Void> released;
        try { released = pool.release(object); }
        catch (Throwable releaseFailure) { released = CompletableFuture.failedFuture(releaseFailure); }
        released.whenComplete((ignored, releaseFailure) -> {
            if (failure != null) {
                if (releaseFailure != null && failure != releaseFailure) failure.addSuppressed(releaseFailure);
                result.completeExceptionally(failure);
            } else if (releaseFailure != null) result.completeExceptionally(releaseFailure);
            else result.complete(value);
        });
    }
}
