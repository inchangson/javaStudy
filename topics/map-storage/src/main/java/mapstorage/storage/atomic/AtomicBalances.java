package mapstorage.storage.atomic;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class AtomicBalances {
    private final Map<String, Long> balances;

    public AtomicBalances(Map<String, Long> initial) {
        var copy = Map.copyOf(initial);
        if (copy.values().stream().anyMatch(value -> value < 0)) {
            throw new IllegalArgumentException("negative balance");
        }
        balances = new HashMap<>(copy);
    }

    public synchronized Map<String, Long> snapshot() { return Map.copyOf(balances); }

    public void transfer(String from, String to, long amount) {
        transfer(from, to, amount, () -> {});
    }

    // Package-private fault injection for the lesson, not an application callback API.
    synchronized void transfer(String from, String to, long amount, Runnable afterDebit) {
        Objects.requireNonNull(afterDebit);
        if (amount <= 0 || Objects.equals(from, to)
                || !balances.containsKey(from) || !balances.containsKey(to)) {
            throw new IllegalArgumentException("invalid transfer");
        }
        long oldFrom = balances.get(from);
        long oldTo = balances.get(to);
        if (oldFrom < amount) throw new IllegalArgumentException("insufficient funds");
        long newTo = Math.addExact(oldTo, amount);
        try {
            balances.put(from, oldFrom - amount);
            afterDebit.run();
            balances.put(to, newTo);
        } catch (RuntimeException | Error failure) {
            balances.put(from, oldFrom);
            balances.put(to, oldTo);
            throw failure;
        }
    }
}
