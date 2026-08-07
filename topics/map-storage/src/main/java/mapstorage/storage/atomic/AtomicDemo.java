package mapstorage.storage.atomic;

import java.util.Map;

public final class AtomicDemo {
    public static void main(String[] args) {
        var balances = new AtomicBalances(Map.of("A", 1000L, "B", 1000L));
        try {
            balances.transfer("A", "B", 100, () -> { throw new IllegalStateException("injected failure"); });
        } catch (IllegalStateException expected) {
            System.out.println("rolled back = " + balances.snapshot());
        }
        balances.transfer("A", "B", 100);
        System.out.println("committed = " + balances.snapshot());
    }
}
