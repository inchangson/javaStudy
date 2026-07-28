package concurrency.virtualthread;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(1)
@Warmup(iterations = 1)
@Measurement(iterations = 3)
@State(Scope.Thread)
public class VirtualThreadCreationBenchmark {
    @Param({"1000"})
    int taskCount;

    @Benchmark
    public int runManyVirtualThreadTasks() throws InterruptedException {
        return VirtualThreadDemo.runManyTasks(taskCount);
    }
}
