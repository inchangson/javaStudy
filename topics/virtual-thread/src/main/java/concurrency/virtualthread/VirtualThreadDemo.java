package concurrency.virtualthread;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

public final class VirtualThreadDemo {
    private VirtualThreadDemo() {
    }

    public static void main(String[] args) throws InterruptedException {
        int taskCount = 1_000;
        int completed = runManyTasks(taskCount);

        System.out.printf("completed %,d / %,d virtual-thread tasks%n", completed, taskCount);
    }

    public static int runManyTasks(int taskCount) throws InterruptedException {
        AtomicInteger completed = new AtomicInteger();
        List<Thread> threads = IntStream.range(0, taskCount)
                .mapToObj(ignored -> Thread.ofVirtual().start(completed::incrementAndGet))
                .toList();

        for (Thread thread : threads) {
            thread.join();
        }

        return completed.get();
    }
}
