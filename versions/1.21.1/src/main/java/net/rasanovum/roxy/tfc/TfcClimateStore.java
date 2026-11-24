package net.rasanovum.roxy.tfc;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.LoggerFactory;

public final class TfcClimateStore implements AutoCloseable {
    private static final int MAGIC = 0x52544643;
    private static final int VERSION = 1;
    private static final int LIMIT = 131072;
    private static final long MAX_FILE = 64L << 20;
    private final LinkedHashMap<Long, float[]> snapshots = new LinkedHashMap<>(16, 0.75F, true);
    private final LinkedHashMap<Long, float[]> pending = new LinkedHashMap<>();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Roxy TFC climate storage");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean flushing = new AtomicBoolean();
    private volatile boolean closed;
    private volatile long loadRevision;
    private Path path;
    private volatile boolean readOnly;
    private boolean repairBeforeAppend;

    public static long key(int x, int z) { return (long) x << 32 | (z & 0xffffffffL); }

    public synchronized float[] get(int x, int z) {
        float[] value = snapshots.get(key(x, z));
        return value == null ? null : value.clone();
    }

    public synchronized boolean contains(int x, int z) {
        return snapshots.containsKey(key(x, z));
    }

    public synchronized boolean put(int x, int z, float[] layers) {
        if (closed || layers == null || layers.length != 16) return false;
        for (float value : layers) if (!Float.isFinite(value)) return false;
        long key = key(x, z);
        if (Arrays.equals(layers, snapshots.get(key))) return false;
        float[] value = layers.clone();
        snapshots.put(key, value);
        pending.put(key, value);
        trim(snapshots);
        trim(pending);
        return true;
    }

    private static void trim(LinkedHashMap<Long, float[]> map) {
        while (map.size() > LIMIT) map.remove(map.keySet().iterator().next());
    }

    public long loadRevision() { return loadRevision; }
    public synchronized int size() { return snapshots.size(); }

    public synchronized void attach(Path file) {
        if (closed || path != null || file == null) return;
        path = file.toAbsolutePath().normalize();
        IO.execute(this::load);
    }

    private void load() {
        boolean needsCompact = false;
        try {
            if (closed || !Files.isRegularFile(path)) return;
            long fileSize = Files.size(path);
            if (fileSize > MAX_FILE * 2) {
                readOnly = true;
                throw new IOException("Oversized climate journal");
            }
            if (fileSize > MAX_FILE) needsCompact = true;
            var loaded = new LinkedHashMap<Long, float[]>();
            int recordsRead = 0;
            try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
                if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                    readOnly = true;
                    throw new IOException("Unsupported climate journal");
                }
                while (true) {
                    long key;
                    try { key = input.readLong(); } catch (EOFException end) { break; }
                    float[] value = new float[16];
                    try { for (int i = 0; i < 16; i++) value[i] = input.readFloat(); }
                    catch (EOFException truncated) {
                        needsCompact = true;
                        break;
                    }
                    recordsRead++;
                    boolean valid = true;
                    for (float entry : value) valid &= Float.isFinite(entry);
                    if (valid) {
                        loaded.remove(key);
                        loaded.put(key, value);
                    } else {
                        needsCompact = true;
                    }
                    trim(loaded);
                }
            }
            if (recordsRead > loaded.size()) needsCompact = true;
            synchronized (this) {
                if (closed) return;
                for (var entry : snapshots.entrySet()) {
                    loaded.remove(entry.getKey());
                    loaded.put(entry.getKey(), entry.getValue());
                }
                trim(loaded);
                snapshots.clear();
                snapshots.putAll(loaded);
                loadRevision++;
            }
            if (needsCompact && !closed) {
                try {
                    compact();
                } catch (IOException failure) {
                    synchronized (this) {
                        repairBeforeAppend = true;
                    }
                    LoggerFactory.getLogger("Roxy").warn("Unable to compact TFC climate sidecar; will retry on next flush", failure);
                }
            }
        } catch (IOException failure) {
            if (!readOnly) {
                synchronized (this) {
                    repairBeforeAppend = true;
                }
            }
            LoggerFactory.getLogger("Roxy").warn("Unable to load TFC climate sidecar; retaining captured climate", failure);
        }
    }

    public synchronized void flushAsync() {
        if (closed || readOnly || path == null || pending.isEmpty() || !flushing.compareAndSet(false, true)) return;
        IO.execute(() -> {
            try { flush(); }
            catch (IOException failure) { LoggerFactory.getLogger("Roxy").warn("Unable to persist TFC climate sidecar", failure); }
            finally { flushing.set(false); }
        });
    }

    private void flush() throws IOException {
        if (readOnly) return;
        Map<Long, float[]> batch;
        boolean repair;
        synchronized (this) {
            batch = new LinkedHashMap<>(pending);
            pending.clear();
            repair = repairBeforeAppend;
        }
        if (batch.isEmpty() && !repair) return;
        try {
            if (repair) {
                compact();
                synchronized (this) {
                    repairBeforeAppend = false;
                }
                return;
            }
            Files.createDirectories(path.getParent());
            boolean header = !Files.exists(path) || Files.size(path) == 0;
            try (var output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND)))) {
                if (header) { output.writeInt(MAGIC); output.writeInt(VERSION); }
                write(output, batch);
            }
            if (Files.size(path) > MAX_FILE) compact();
        } catch (IOException failure) {
            synchronized (this) {
                repairBeforeAppend = true;
                batch.forEach(pending::putIfAbsent);
                trim(pending);
            }
            throw failure;
        }
    }

    private void compact() throws IOException {
        Map<Long, float[]> copy;
        synchronized (this) { copy = new LinkedHashMap<>(snapshots); }
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "climate-", ".tmp");
        try {
            try (var output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                write(output, copy);
            }
            try { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void write(DataOutputStream output, Map<Long, float[]> data) throws IOException {
        for (var entry : data.entrySet()) {
            output.writeLong(entry.getKey());
            for (float value : entry.getValue()) output.writeFloat(value);
        }
    }

    @Override
    public void close() {
        Future<?> finalFlush = null;
        synchronized (this) {
            if (closed) return;
            closed = true;
            if (path != null && !readOnly) {
                finalFlush = IO.submit(() -> {
                    try { flush(); }
                    catch (IOException failure) { LoggerFactory.getLogger("Roxy").warn("Unable to finish TFC climate sidecar", failure); }
                });
            }
        }
        if (finalFlush != null) {
            try {
                finalFlush.get(2L, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException ignored) {
            }
        }
    }
}
