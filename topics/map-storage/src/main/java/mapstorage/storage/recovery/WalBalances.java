package mapstorage.storage.recovery;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;
import static java.nio.file.StandardOpenOption.*;

/** Teaching WAL: one writer, two fixed accounts, redo full after-images on COMMIT. */
public final class WalBalances implements AutoCloseable {
    static final int FRAME_BYTES = 36;
    private static final int MAGIC = 0x4D415031; // MAP1, big endian
    private static final int BEGIN = 1;
    private static final int COMMIT = 2;
    enum Stage { BEFORE_COMMIT, AFTER_COMMIT }
    @FunctionalInterface interface CrashPoint { void reached(Stage stage); }
    private record Frame(int kind, long tx, long a, long b) {}

    private final FileChannel log;
    private final FileLock writerLock;
    private final Map<String, Long> balances = new HashMap<>(Map.of("A", 1000L, "B", 1000L));
    private long lastTx;
    private boolean failed;

    public WalBalances(Path path) throws IOException {
        log = FileChannel.open(path, CREATE, READ, WRITE);
        try {
            writerLock = log.tryLock();
            if (writerLock == null) throw new IOException("another writer owns this log");
            recover();
        } catch (IOException | RuntimeException | Error failure) {
            try { log.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            if (failure instanceof OverlappingFileLockException) {
                throw new IOException("another writer owns this log", failure);
            }
            throw failure;
        }
    }

    public synchronized Map<String, Long> snapshot() {
        ensureUsable();
        return Map.copyOf(balances);
    }

    public void transfer(String from, String to, long amount) throws IOException {
        transfer(from, to, amount, stage -> {});
    }

    synchronized void transfer(String from, String to, long amount, CrashPoint crashPoint) throws IOException {
        ensureUsable();
        if (amount <= 0 || from == null || from.equals(to)
                || !balances.containsKey(from) || !balances.containsKey(to)) {
            throw new IllegalArgumentException("invalid transfer");
        }
        if (balances.get(from) < amount) throw new IllegalArgumentException("insufficient funds");
        var next = new HashMap<>(balances);
        next.put(from, next.get(from) - amount);
        next.put(to, Math.addExact(next.get(to), amount));
        long tx = Math.incrementExact(lastTx);
        try {
            append(new Frame(BEGIN, tx, next.get("A"), next.get("B")));
            log.force(true);
            crashPoint.reached(Stage.BEFORE_COMMIT);
            append(new Frame(COMMIT, tx, next.get("A"), next.get("B")));
            log.force(true);
            crashPoint.reached(Stage.AFTER_COMMIT);
            balances.putAll(next);
            lastTx = tx;
        } catch (IOException | RuntimeException | Error failure) {
            // A failed I/O may have persisted COMMIT. Reopen and recover before any further use.
            failed = true;
            throw failure;
        }
    }

    private void append(Frame frame) throws IOException {
        var bytes = ByteBuffer.allocate(FRAME_BYTES);
        bytes.putInt(MAGIC).putInt(frame.kind).putLong(frame.tx).putLong(frame.a).putLong(frame.b);
        var crc = new CRC32();
        crc.update(bytes.array(), 0, FRAME_BYTES - Integer.BYTES);
        bytes.putInt((int) crc.getValue()).flip();
        while (bytes.hasRemaining()) log.write(bytes);
    }

    private void recover() throws IOException {
        log.position(0);
        long committedEnd = 0;
        Frame pending = null;
        while (true) {
            var bytes = ByteBuffer.allocate(FRAME_BYTES);
            while (bytes.hasRemaining() && log.read(bytes) != -1) { /* partial reads */ }
            if (bytes.position() != FRAME_BYTES) break; // incomplete final frame
            var crc = new CRC32();
            crc.update(bytes.array(), 0, FRAME_BYTES - Integer.BYTES);
            bytes.flip();
            int magic = bytes.getInt();
            var frame = new Frame(bytes.getInt(), bytes.getLong(), bytes.getLong(), bytes.getLong());
            int checksum = bytes.getInt();
            if (magic != MAGIC || checksum != (int) crc.getValue()) {
                throw new IOException("corrupt complete WAL frame at " + (log.position() - FRAME_BYTES));
            }
            if (frame.a < 0 || frame.b < 0 || frame.a > 2000 || frame.b != 2000 - frame.a) {
                throw new IOException("invalid balances in WAL");
            }
            if (frame.kind == BEGIN && pending == null && frame.tx == lastTx + 1) {
                pending = frame;
            } else if (frame.kind == COMMIT && pending != null && frame.tx == pending.tx
                    && frame.a == pending.a && frame.b == pending.b) {
                balances.put("A", frame.a);
                balances.put("B", frame.b);
                lastTx = frame.tx;
                committedEnd = log.position();
                pending = null;
            } else {
                throw new IOException("invalid WAL transaction sequence");
            }
        }
        // Discard only an uncommitted suffix; never silently discard complete corrupt frames.
        if (log.size() != committedEnd) {
            log.truncate(committedEnd);
            log.force(true);
        }
        log.position(committedEnd);
    }

    private void ensureUsable() {
        if (failed || !log.isOpen()) throw new IllegalStateException("close and reopen the store");
    }

    @Override public synchronized void close() throws IOException {
        try { if (writerLock.isValid()) writerLock.release(); }
        finally { log.close(); }
    }
}
