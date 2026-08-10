package mapstorage.storage.recovery;

import java.nio.file.Path;

public final class RecoveryDemo {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("provide a WAL file path");
        var path = Path.of(args[0]);
        try (var store = new WalBalances(path)) {
            System.out.println("recovered = " + store.snapshot());
            store.transfer("A", "B", 100);
            System.out.println("committed = " + store.snapshot());
        }
        try (var reopened = new WalBalances(path)) {
            System.out.println("reopened = " + reopened.snapshot());
        }
    }
}
