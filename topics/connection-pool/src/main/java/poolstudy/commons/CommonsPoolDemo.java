package poolstudy.commons;

import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public final class CommonsPoolDemo {
    public static final class Resource {
        public final int id;
        public String state = "";
        public boolean valid = true;
        public boolean destroyed;
        private Resource(int id) { this.id = id; }
    }

    public static final class Factory extends BasePooledObjectFactory<Resource> {
        private final AtomicInteger ids = new AtomicInteger();
        public final List<String> events = new CopyOnWriteArrayList<>();
        @Override public Resource create() {
            var resource = new Resource(ids.incrementAndGet());
            events.add("create " + resource.id);
            return resource;
        }
        @Override public PooledObject<Resource> wrap(Resource object) {
            return new DefaultPooledObject<>(object);
        }
        @Override public void activateObject(PooledObject<Resource> p) {
            events.add("activate " + p.getObject().id);
        }
        @Override public boolean validateObject(PooledObject<Resource> p) {
            events.add("validate " + p.getObject().id);
            return p.getObject().valid && !p.getObject().destroyed;
        }
        @Override public void passivateObject(PooledObject<Resource> p) {
            events.add("passivate " + p.getObject().id);
            p.getObject().state = "";
        }
        @Override public void destroyObject(PooledObject<Resource> p) {
            events.add("destroy " + p.getObject().id);
            p.getObject().destroyed = true;
        }
    }

    public static GenericObjectPool<Resource> newPool(Factory factory) {
        var config = new GenericObjectPoolConfig<Resource>();
        config.setMaxTotal(1);
        config.setMaxIdle(1);
        config.setBlockWhenExhausted(true);
        config.setMaxWait(Duration.ofMillis(100));
        config.setTestOnBorrow(true);
        config.setJmxEnabled(false);
        return new GenericObjectPool<>(factory, config);
    }

    public static void main(String[] args) throws Exception {
        var factory = new Factory();
        try (var pool = newPool(factory)) {
            var first = pool.borrowObject();
            try { first.state = "transaction state"; }
            finally { pool.returnObject(first); }
            // Teaching fault injection: a formerly idle connection has died.
            first.valid = false;
            var replacement = pool.borrowObject();
            try {
                System.out.println("replaced=" + (first != replacement));
                System.out.println("active=" + pool.getNumActive() + ", idle=" + pool.getNumIdle());
            } finally { pool.returnObject(replacement); }
        }
        factory.events.forEach(System.out::println);
    }
}
