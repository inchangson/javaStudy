package mapstorage.concurrency;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

public final class CounterDemo {
    // Both workers finish reading before either writes: lost update is reproducible.
    public static int lostUpdate(Map<String, Integer> map) throws Exception {
        map.put("count", 0);
        var bothRead = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Callable<Void> increment = () -> {
                int before = map.get("count");
                bothRead.await(5, TimeUnit.SECONDS);
                map.put("count", before + 1);
                return null;
            };
            var first = workers.submit(increment);
            var second = workers.submit(increment);
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        return map.get("count");
    }

    public static int incrementSafely(Map<String, Integer> map, boolean externalLock) throws Exception {
        map.put("count", 0);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Callable<Void> increment = () -> {
                for (int i = 0; i < 1_000; i++) {
                    if (externalLock) {
                        synchronized (map) { map.put("count", map.get("count") + 1); }
                    } else {
                        map.compute("count", (key, value) -> value + 1);
                    }
                }
                return null;
            };
            var first = workers.submit(increment);
            var second = workers.submit(increment);
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        return map.get("count");
    }

    public static void main(String[] args) throws Exception {
        System.out.println("synchronizedMap get+put = " + lostUpdate(Collections.synchronizedMap(new HashMap<>())));
        System.out.println("ConcurrentHashMap get+put = " + lostUpdate(new ConcurrentHashMap<>()));
        System.out.println("external lock = " + incrementSafely(Collections.synchronizedMap(new HashMap<>()), true));
        System.out.println("compute = " + incrementSafely(new ConcurrentHashMap<>(), false));
    }
}
