package poolstudy.hikari;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.h2.jdbc.JdbcConnection;
import java.sql.SQLException;
import java.util.UUID;

public final class HikariDemo {
    public record Observation(boolean physicalReused, boolean proxyReplaced,
                              boolean autoCommitReset, int committedRows) { }

    public static HikariDataSource newPool(int size) {
        return newPool(size, 300);
    }

    public static HikariDataSource newPool(int size, long timeoutMs) {
        var config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:pool_" + UUID.randomUUID());
        config.setMaximumPoolSize(size);
        config.setMinimumIdle(size);
        config.setConnectionTimeout(timeoutMs); // Hikari enforces a minimum of 250ms.
        config.setPoolName("lesson-" + UUID.randomUUID());
        return new HikariDataSource(config);
    }

    public static Observation observe(HikariDataSource pool) throws SQLException {
        var first = pool.getConnection();
        JdbcConnection physical;
        try (first) {
            physical = first.unwrap(JdbcConnection.class); // Inspection only; do not mutate the delegate.
            try (var statement = first.createStatement()) {
                statement.execute("CREATE TABLE sample(id INT PRIMARY KEY)");
            }
            first.setAutoCommit(false);
            try (var statement = first.prepareStatement("INSERT INTO sample VALUES (?)")) {
                statement.setInt(1, 1);
                statement.executeUpdate();
            }
            // Intentionally no commit: ProxyConnection.close must roll back.
        }
        try (var second = pool.getConnection();
             var statement = second.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM sample")) {
            rows.next();
            return new Observation(physical == second.unwrap(JdbcConnection.class),
                    first != second, second.getAutoCommit(), rows.getInt(1));
        }
    }

    public static void main(String[] args) throws Exception {
        try (var pool = newPool(1)) {
            System.out.println(observe(pool));
            System.out.println("active=" + pool.getHikariPoolMXBean().getActiveConnections()
                    + ", idle=" + pool.getHikariPoolMXBean().getIdleConnections());
        }
    }
}
