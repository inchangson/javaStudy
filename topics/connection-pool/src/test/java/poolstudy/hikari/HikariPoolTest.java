package poolstudy.hikari;

import org.h2.jdbc.JdbcConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.sql.SQLTransientConnectionException;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class HikariPoolTest {
    @Test void proxyCloseRollsBackResetsAndReusesPhysicalConnection() throws Exception {
        JdbcConnection physical;
        try (var pool = HikariDemo.newPool(1)) {
            assertEquals(new HikariDemo.Observation(true, true, true, 0), HikariDemo.observe(pool));
            var lease = pool.getConnection();
            physical = lease.unwrap(JdbcConnection.class);
            lease.close();
            lease.close();
            assertFalse(physical.isClosed());
            assertThrows(java.sql.SQLException.class, lease::createStatement);
            assertEquals(1, pool.getHikariPoolMXBean().getIdleConnections());
        }
        assertTrue(physical.isClosed());
    }

    @Test void heldConnectionCausesAcquisitionTimeout() throws Exception {
        try (var pool = HikariDemo.newPool(1); var held = pool.getConnection()) {
            assertThrows(SQLTransientConnectionException.class, pool::getConnection);
            assertEquals(1, pool.getHikariPoolMXBean().getActiveConnections());
        }
    }

    @Test void returnHandsConnectionToWaitingBorrower() throws Exception {
        try (var pool = HikariDemo.newPool(1, 3000);
             var executor = Executors.newSingleThreadExecutor()) {
            var held = pool.getConnection();
            try {
                var future = executor.submit(() -> {
                    try (var next = pool.getConnection()) { return next.isValid(1); }
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (pool.getHikariPoolMXBean().getThreadsAwaitingConnection() == 0
                        && !future.isDone() && System.nanoTime() < deadline) Thread.sleep(1);
                assertEquals(1, pool.getHikariPoolMXBean().getThreadsAwaitingConnection());
                held.close();
                assertTrue(future.get(2, TimeUnit.SECONDS));
            } finally { held.close(); }
        }
    }
}
