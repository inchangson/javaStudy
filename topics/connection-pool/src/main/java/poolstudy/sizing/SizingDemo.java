package poolstudy.sizing;

import com.zaxxer.hikari.HikariDataSource;
import poolstudy.hikari.HikariDemo;
import poolstudy.tiny.TinyPool;
import java.time.Duration;
import java.sql.SQLTransientConnectionException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/** Controlled queueing experiments, not a microbenchmark ranking pool implementations. */
public final class SizingDemo {
    public enum Kind { MODEL, H2_ROW_LOCK }
    public record Sample(long waitNs, long holdNs, long backendNs, long totalNs, boolean success) { }
    public record Result(Kind kind, int size, int clients, int operations, long elapsedNs,
                         long verifiedUpdates, List<Sample> samples) {
        public long successes() { return samples.stream().filter(Sample::success).count(); }
        public long timeouts() { return samples.size() - successes(); }
        public double throughput() { return successes() * 1_000_000_000.0 / elapsedNs; }
        public String csv(int round) {
            var ok = samples.stream().filter(Sample::success).toList();
            return String.format(Locale.ROOT, "%s,%d,%d,%d,%d,%d,%d,%.3f,%.2f,%.3f,%.3f,%.3f,%.3f,%.3f",
                    kind, round, size, clients, operations, successes(), timeouts(), elapsedNs / 1e6, throughput(),
                    percentile(ok.stream().mapToLong(Sample::waitNs).toArray(), .95) / 1e6,
                    ok.stream().mapToLong(Sample::holdNs).average().orElse(0) / 1e6,
                    ok.stream().mapToLong(Sample::backendNs).average().orElse(0) / 1e6,
                    percentile(samples.stream().mapToLong(Sample::totalNs).toArray(), .50) / 1e6,
                    percentile(samples.stream().mapToLong(Sample::totalNs).toArray(), .95) / 1e6);
        }
    }
    private interface Handle extends AutoCloseable {
        long work() throws Exception;
        @Override void close() throws Exception;
    }
    private interface BackendPool extends AutoCloseable {
        Handle borrow() throws Exception;
        int completed() throws Exception;
        @Override void close() throws Exception;
    }

    /** Explicit assumption: useful concurrency is four, excess concurrency adds quadratic cost. */
    public static long modeledServiceNanos(int active) {
        if (active < 1) throw new IllegalArgumentException("active must be positive");
        long excess = Math.max(0, active - 4);
        return 2_000_000L + 500_000L * excess * excess;
    }

    private static BackendPool modelPool(int size) {
        var objects = IntStream.range(0, size).mapToObj(i -> new Object()).toList();
        var pool = new TinyPool<>(objects);
        var active = new AtomicInteger();
        var completed = new AtomicInteger();
        return new BackendPool() {
            public Handle borrow() throws Exception {
                var lease = pool.borrow(Duration.ofSeconds(10));
                return new Handle() {
                    public long work() throws InterruptedException {
                        long start = System.nanoTime();
                        int concurrency = active.incrementAndGet();
                        try {
                            TimeUnit.NANOSECONDS.sleep(modeledServiceNanos(concurrency));
                            completed.incrementAndGet();
                            return System.nanoTime() - start;
                        } finally { active.decrementAndGet(); }
                    }
                    public void close() { lease.close(); }
                };
            }
            public int completed() { return completed.get(); }
            public void close() { pool.close(); }
        };
    }

    private static BackendPool h2Pool(int size) throws Exception {
        HikariDataSource pool = HikariDemo.newPool(size, 10_000);
        try (var connection = pool.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE counter(id INT PRIMARY KEY, value_count INT)");
            statement.execute("INSERT INTO counter VALUES (1, 0)");
        } catch (Exception failure) { pool.close(); throw failure; }
        // Fill and validate all slots before warmup/measurement; minIdle filling is asynchronous.
        var held = new ArrayList<java.sql.Connection>();
        try {
            for (int i = 0; i < size; i++) held.add(pool.getConnection());
        } catch (Exception failure) { pool.close(); throw failure; }
        finally { for (var connection : held) connection.close(); }
        return new BackendPool() {
            public Handle borrow() throws Exception {
                var connection = pool.getConnection();
                return new Handle() {
                    public long work() throws Exception {
                        connection.setAutoCommit(false);
                        long start;
                        long sqlNs;
                        try (var statement = connection.prepareStatement(
                                "UPDATE counter SET value_count=value_count+1 WHERE id=1")) {
                            start = System.nanoTime();
                            statement.executeUpdate(); // Includes row-lock wait, but is not a pure lock timer.
                            sqlNs = System.nanoTime() - start;
                        }
                        Thread.sleep(2); // Deliberately hold the same row lock for every transaction.
                        connection.commit();
                        return sqlNs;
                    }
                    public void close() throws Exception { connection.close(); }
                };
            }
            public int completed() throws Exception {
                try (var connection = pool.getConnection(); var statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT value_count FROM counter WHERE id=1")) {
                    rows.next();
                    return rows.getInt(1);
                }
            }
            public void close() { pool.close(); }
        };
    }

    public static Result run(Kind kind, int size, int clients, int operationsPerClient) throws Exception {
        if (size < 1 || clients < 1 || operationsPerClient < 1) throw new IllegalArgumentException("positive counts required");
        try (var pool = kind == Kind.MODEL ? modelPool(size) : h2Pool(size)) {
            batch(pool, clients, 2); // Warmup is excluded from results and update counts below.
            int before = pool.completed();
            var measured = batch(pool, clients, operationsPerClient);
            int updates = pool.completed() - before;
            long successes = measured.samples.stream().filter(Sample::success).count();
            if (updates != successes) throw new IllegalStateException("lost or unaccounted update: " + updates + "/" + successes);
            return new Result(kind, size, clients, clients * operationsPerClient,
                    measured.elapsedNs, updates, measured.samples);
        }
    }
    private record Batch(long elapsedNs, List<Sample> samples) { }
    private static Batch batch(BackendPool pool, int clients, int operationsPerClient) throws Exception {
        var start = new CountDownLatch(1);
        var ready = new CountDownLatch(clients);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<List<Sample>>>();
            for (int i = 0; i < clients; i++) futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                var samples = new ArrayList<Sample>();
                for (int j = 0; j < operationsPerClient; j++) {
                    long requested = System.nanoTime();
                    long acquired;
                    long backendNs;
                    try (var handle = pool.borrow()) {
                        acquired = System.nanoTime();
                        backendNs = handle.work();
                    } catch (TimeoutException | SQLTransientConnectionException timeout) {
                        long elapsed = System.nanoTime() - requested;
                        samples.add(new Sample(elapsed, 0, 0, elapsed, false));
                        continue;
                    }
                    long ended = System.nanoTime();
                    samples.add(new Sample(acquired - requested, ended - acquired, backendNs, ended - requested, true));
                }
                return samples;
            }));
            if (!ready.await(5, TimeUnit.SECONDS)) {
                start.countDown();
                throw new IllegalStateException("workers did not start");
            }
            long began = System.nanoTime();
            start.countDown();
            var samples = new ArrayList<Sample>();
            for (var future : futures) samples.addAll(future.get(30, TimeUnit.SECONDS));
            return new Batch(System.nanoTime() - began, List.copyOf(samples));
        }
    }

    public static long percentile(long[] values, double quantile) {
        if (values.length == 0) return 0;
        Arrays.sort(values);
        return values[Math.max(0, (int) Math.ceil(values.length * quantile) - 1)];
    }

    public static void main(String[] args) throws Exception {
        System.out.println("scenario,round,pool,clients,operations,success,timeouts,elapsed_ms,throughput_ops_s,acquire_p95_ms,hold_mean_ms,backend_mean_ms,total_p50_ms,total_p95_ms");
        var random = new Random(8112026);
        for (int round = 1; round <= 3; round++) {
            var sizes = new ArrayList<>(List.of(1, 2, 4, 8, 16));
            Collections.shuffle(sizes, random);
            for (int size : sizes) {
                for (var kind : Kind.values()) System.out.println(run(kind, size, 24, 12).csv(round));
            }
        }
    }
}
