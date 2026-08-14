package poolstudy.tiny;

import java.time.Duration;
import java.util.List;

public final class TinyPoolDemo {
    public static void main(String[] args) throws Exception {
        var buffer = new StringBuilder();
        try (var pool = new TinyPool<>(List.of(buffer))) {
            try (var lease = pool.borrow(Duration.ofSeconds(1))) {
                lease.get().append("previous borrower");
            }
            try (var lease = pool.borrow(Duration.ofSeconds(1))) {
                System.out.println("same object=" + (buffer == lease.get()));
                System.out.println("state left behind=" + lease.get());
                lease.get().setLength(0);
            }
        }
    }
}
