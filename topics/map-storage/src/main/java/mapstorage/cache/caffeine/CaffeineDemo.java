package mapstorage.cache.caffeine;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;

public final class CaffeineDemo {
    /** Single-threaded lab control: deliberately postpones maintenance until runPending. */
    public static final class ManualExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable command) { tasks.add(command); }
        public int pendingTasks() { return tasks.size(); }
        public void runPending() {
            Runnable task;
            while ((task = tasks.poll()) != null) task.run();
        }
    }

    public static void main(String[] args) {
        var executor = new ManualExecutor();
        var cache = Caffeine.newBuilder().maximumSize(2).executor(executor)
                .<String, Integer>build();
        cache.put("A", 1); cache.put("B", 2); cache.put("C", 3);
        System.out.println("value before policy maintenance = " + cache.getIfPresent("A"));
        System.out.println("size before maintenance = " + cache.estimatedSize());
        cache.cleanUp();
        executor.runPending();
        System.out.println("size after maintenance = " + cache.estimatedSize());
    }
}
