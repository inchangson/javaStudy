package concurrency.virtualthread;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VirtualThreadDemoTest {
    @Test
    void runsAllVirtualThreadTasks() throws InterruptedException {
        assertEquals(1_000, VirtualThreadDemo.runManyTasks(1_000));
    }
}
