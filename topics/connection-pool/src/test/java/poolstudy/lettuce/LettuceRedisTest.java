package poolstudy.lettuce;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Tag("redis")
@Timeout(30)
class LettuceRedisTest {
    @Test void realRedisShowsTransactionLeakAndBothPoolingAdapters() throws Exception {
        String uri = System.getenv("REDIS_URI");
        assertNotNull(uri, "REDIS_URI is required; run scripts/verify-redis.sh");
        assertEquals(new LettuceDemo.Observation(true, "PONG", "PONG"), LettuceDemo.observe(uri));
    }
}
