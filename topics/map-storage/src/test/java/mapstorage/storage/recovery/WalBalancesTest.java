package mapstorage.storage.recovery;

import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class WalBalancesTest {
    @TempDir Path directory;

    @Test void committedTransfersSurviveMultipleReopens() throws Exception {
        var path = directory.resolve("balances.wal");
        try (var store = new WalBalances(path)) {
            store.transfer("A", "B", 100);
            store.transfer("B", "A", 30);
        }
        for (int i = 0; i < 2; i++) {
            try (var store = new WalBalances(path)) {
                assertEquals(Map.of("A", 930L, "B", 1070L), store.snapshot());
            }
        }
        assertEquals(4L * WalBalances.FRAME_BYTES, Files.size(path));
    }
    @Test void childCrashBeforeCommitDiscardsPreparedChangeAndCanContinue() throws Exception {
        var path = directory.resolve("before.wal");
        crash(path, WalBalances.Stage.BEFORE_COMMIT);
        assertEquals(WalBalances.FRAME_BYTES, Files.size(path));
        try (var store = new WalBalances(path)) {
            assertEquals(Map.of("A", 1000L, "B", 1000L), store.snapshot());
            assertEquals(0, Files.size(path));
            store.transfer("A", "B", 50);
        }
        try (var store = new WalBalances(path)) {
            assertEquals(Map.of("A", 950L, "B", 1050L), store.snapshot());
        }
    }
    @Test void childCrashAfterCommitReplaysBeforeMemoryWasUpdated() throws Exception {
        var path = directory.resolve("after.wal");
        crash(path, WalBalances.Stage.AFTER_COMMIT);
        try (var store = new WalBalances(path)) {
            assertEquals(Map.of("A", 900L, "B", 1100L), store.snapshot());
        }
    }
    @Test void everyPartialFinalTransactionPreservesPreviousCommit() throws Exception {
        var source = directory.resolve("source.wal");
        try (var store = new WalBalances(source)) {
            store.transfer("A", "B", 100);
            store.transfer("A", "B", 50);
        }
        byte[] complete = Files.readAllBytes(source);
        int pairBytes = 2 * WalBalances.FRAME_BYTES;
        for (int tail = 1; tail < pairBytes; tail++) {
            var path = directory.resolve("partial-" + tail + ".wal");
            Files.write(path, java.util.Arrays.copyOf(complete, pairBytes + tail));
            try (var store = new WalBalances(path)) {
                assertEquals(Map.of("A", 900L, "B", 1100L), store.snapshot(), "tail=" + tail);
                assertEquals(pairBytes, Files.size(path));
                store.transfer("B", "A", 10);
            }
            try (var store = new WalBalances(path)) {
                assertEquals(Map.of("A", 910L, "B", 1090L), store.snapshot());
            }
        }
    }
    @Test void completeCorruptionFailsWithoutTruncating() throws Exception {
        var path = directory.resolve("corrupt.wal");
        try (var store = new WalBalances(path)) { store.transfer("A", "B", 100); }
        var bytes = Files.readAllBytes(path);
        bytes[20] ^= 1;
        Files.write(path, bytes);
        assertThrows(java.io.IOException.class, () -> new WalBalances(path));
        assertArrayEquals(bytes, Files.readAllBytes(path));
    }
    @Test void secondWriterAndInvalidTransferAreRejected() throws Exception {
        var path = directory.resolve("locked.wal");
        try (var store = new WalBalances(path)) {
            assertThrows(java.io.IOException.class, () -> new WalBalances(path));
            assertThrows(IllegalArgumentException.class, () -> store.transfer("A", "B", 1001));
            assertEquals(0, Files.size(path));
            assertEquals(Map.of("A", 1000L, "B", 1000L), store.snapshot());
        }
    }
    @Test void failureAfterDurableCommitRequiresReopenToResolveOutcome() throws Exception {
        var path = directory.resolve("uncertain.wal");
        try (var store = new WalBalances(path)) {
            assertThrows(IllegalStateException.class, () -> store.transfer("A", "B", 100, stage -> {
                if (stage == WalBalances.Stage.AFTER_COMMIT) throw new IllegalStateException("lost acknowledgement");
            }));
            assertThrows(IllegalStateException.class, store::snapshot);
            assertThrows(IllegalStateException.class, () -> store.transfer("A", "B", 1));
        }
        try (var store = new WalBalances(path)) {
            assertEquals(Map.of("A", 900L, "B", 1100L), store.snapshot());
        }
    }

    private void crash(Path path, WalBalances.Stage stage) throws Exception {
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var output = directory.resolve(stage + ".log");
        var child = new ProcessBuilder(java, "-cp", System.getProperty("study.crash.classpath"),
                CrashWriter.class.getName(), path.toString(), stage.name())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(child.waitFor(15, TimeUnit.SECONDS), "child timed out");
            assertEquals(23, child.exitValue(), Files.readString(output));
        } finally {
            if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
        }
    }
}
