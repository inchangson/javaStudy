package mapstorage.storage.recovery;

import java.nio.file.Path;

/** A child JVM only: halt intentionally skips close and shutdown hooks. */
public final class CrashWriter {
    public static void main(String[] args) throws Exception {
        var target = WalBalances.Stage.valueOf(args[1]);
        try (var store = new WalBalances(Path.of(args[0]))) {
            store.transfer("A", "B", 100, stage -> {
                if (stage == target) Runtime.getRuntime().halt(23);
            });
        }
        throw new AssertionError("crash point was not reached");
    }
}
