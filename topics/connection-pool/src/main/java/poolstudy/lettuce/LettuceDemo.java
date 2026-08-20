package poolstudy.lettuce;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.support.*;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import java.time.Duration;

public final class LettuceDemo {
    public record Observation(boolean multiLeaked, String ping, String asyncPing) { }

    public static Observation observe(String uri) throws Exception {
        var client = RedisClient.create(uri);
        try {
            var config = new GenericObjectPoolConfig<StatefulRedisConnection<String, String>>();
            config.setMaxTotal(1);
            config.setMaxIdle(1);
            config.setMaxWait(Duration.ofMillis(100));
            config.setTestOnBorrow(true);
            config.setJmxEnabled(false);
            boolean leaked;
            String ping;
            try (var pool = ConnectionPoolSupport.createGenericObjectPool(client::connect, config)) {
                try (var first = pool.borrowObject()) {
                    first.sync().multi(); // Deliberately incomplete Redis transaction.
                }
                try (var second = pool.borrowObject()) {
                    leaked = second.isMulti();
                    second.sync().discard(); // Explicit application cleanup before reusing.
                    ping = second.sync().ping();
                }
            }
            var bounded = BoundedPoolConfig.builder().maxTotal(1).maxIdle(1).minIdle(0)
                    .testOnAcquire(true).build();
            // Raw connections: AsyncLease.use owns release. closeAsync would close the socket.
            var pool = AsyncConnectionPoolSupport.createBoundedObjectPoolAsync(
                    () -> client.connectAsync(StringCodec.UTF8, RedisURI.create(uri)), bounded, false)
                    .toCompletableFuture().join();
            try {
                String asyncPing = AsyncLease.use(pool, c -> c.async().ping()).toCompletableFuture().join();
                return new Observation(leaked, ping, asyncPing);
            } finally { pool.closeAsync().join(); }
        } finally { client.shutdown(); }
    }

    public static void main(String[] args) throws Exception {
        String uri = System.getenv("REDIS_URI");
        if (uri == null) throw new IllegalArgumentException("Set REDIS_URI or run scripts/verify-redis.sh");
        System.out.println(observe(uri));
    }
}
