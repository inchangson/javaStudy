package poolstudy.sizing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class SizingTest {
    @Test void measuredH2UpdatesSurviveContentionAndExcludeWarmup() throws Exception {
        var result = SizingDemo.run(SizingDemo.Kind.H2_ROW_LOCK, 4, 8, 3);
        assertEquals(24, result.operations());
        assertEquals(24, result.successes());
        assertEquals(24, result.verifiedUpdates());
        assertEquals(0, result.timeouts());
        assertTrue(result.samples().stream().allMatch(s ->
                s.totalNs() == s.waitNs() + s.holdNs() && s.backendNs() <= s.holdNs()));
    }
    @Test void modelAccountsForAllRequestsWithoutAssertingMachineSpeed() throws Exception {
        var result = SizingDemo.run(SizingDemo.Kind.MODEL, 2, 8, 3);
        assertEquals(24, result.successes());
        assertEquals(24, result.verifiedUpdates());
        assertEquals(0, result.timeouts());
    }
}
