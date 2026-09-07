package net.rasanovum.roxy.tfc;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.LoggerFactory;

/** A bounded climate journal; disk work never runs on a render or mesh thread. */
public final class TfcClimateStore implements AutoCloseable {
    private static final int MAGIC = 0x52544643;
    private static final int VERSION = 1;
    private static final int LIMIT = 131072;
    private static final long MAX_FILE = 64L << 20;
    private final LinkedHashMap<Long, float[]> snapshots = new LinkedHashMap<>();
    private final LinkedHashMap<Long, float[]> pending = new LinkedHashMap<>();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Roxy TFC climate storage");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean flushing = new AtomicBoolean();
    private volatile boolean closed;
    private volatile long revision;
    private volatile long loadRevision;
    private Path path;
    private volatile boolean readOnly;
    private boolean repairBeforeAppend;

    public static long key(int x, int z) { return (long) x << 32 | (z & 0xffffffffL); }

    public synchronized float[] get(int x, int z) {
        float[] value = snapshots.get(key(x,z));
        return value == null ? null : value.clone();
    }

    public synchronized boolean contains(int x, int z) {
        return snapshots.containsKey(key(x, z));
    }

    public synchronized boolean put(int x, int z, float[] layers) {
        if (closed || layers == null || layers.length != 16) return false;
        for (float value : layers) if (!Float.isFinite(value)) return false;
        long key = key(x,z);
        if (Arrays.equals(layers,snapshots.get(key))) return false;
        float[] value = layers.clone();
        snapshots.remove(key);
        snapshots.put(key,value);
        pending.put(key,value);
        trim(snapshots); trim(pending);
        revision++;
        return true;
    }

    private static void trim(LinkedHashMap<Long,float[]> map) {
        while (map.size() > LIMIT) map.remove(map.keySet().iterator().next());
    }

    public long revision() { return revision; }
    public long loadRevision() { return loadRevision; }
    public synchronized int size() { return snapshots.size(); }

    public synchronized void attach(Path file) {
        if (closed || path != null || file == null) return;
        path = file.toAbsolutePath().normalize();
        IO.execute(this::load);
    }

    private void load() {
        try {
            if (!Files.isRegularFile(path)) return;
            if (Files.size(path) > MAX_FILE * 2) throw new IOException("Oversized climate journal");
            var loaded = new LinkedHashMap<Long,float[]>();
            try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
                if (input.readInt() != MAGIC || input.readInt() != VERSION) throw new IOException("Unsupported climate journal");
                while (true) {
                    long key;
                    try { key = input.readLong(); } catch (EOFException end) { break; }
                    float[] value = new float[16];
                    try { for(int i=0;i<16;i++) value[i]=input.readFloat(); }
                    catch (EOFException truncated) { break; }
                    boolean valid=true;
                    for(float entry:value)valid &= Float.isFinite(entry);
                    if(valid) {
                        loaded.remove(key);
                        loaded.put(key,value);
                    }
                    trim(loaded);
                }
            }
            synchronized(this) {
                for(var entry:snapshots.entrySet()) {
                    loaded.remove(entry.getKey());
                    loaded.put(entry.getKey(),entry.getValue());
                }
                trim(loaded);
                snapshots.clear();
                snapshots.putAll(loaded);
                revision++;
                loadRevision++;
            }
            // Rewrite once after loading so an interrupted trailing record cannot poison later appends.
            compact();
        } catch (IOException failure) {
            readOnly = true;
            LoggerFactory.getLogger("Roxy").warn("Unable to load TFC climate sidecar; retaining captured climate",failure);
        }
    }

    public synchronized void flushAsync() {
        if (closed || readOnly || path == null || pending.isEmpty() || !flushing.compareAndSet(false,true)) return;
        IO.execute(() -> {
            try { flush(); }
            catch(IOException failure) { LoggerFactory.getLogger("Roxy").warn("Unable to persist TFC climate sidecar",failure); }
            finally { flushing.set(false); }
        });
    }

    private void flush() throws IOException {
        if (readOnly) return;
        Map<Long,float[]> batch;
        synchronized(this) { batch = new LinkedHashMap<>(pending); pending.clear(); }
        if(batch.isEmpty())return;
        try {
            if (repairBeforeAppend) {
                compact();
                repairBeforeAppend = false;
                return;
            }
            Files.createDirectories(path.getParent());
            boolean header=!Files.exists(path) || Files.size(path)==0;
            try(var output=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path,StandardOpenOption.CREATE,StandardOpenOption.APPEND)))) {
                if(header){output.writeInt(MAGIC);output.writeInt(VERSION);}
                write(output,batch);
            }
            if(Files.size(path)>MAX_FILE)compact();
        } catch(IOException failure) {
            repairBeforeAppend = true;
            synchronized(this) { batch.forEach(pending::putIfAbsent); trim(pending); }
            throw failure;
        }
    }

    private void compact() throws IOException {
        Map<Long,float[]> copy;
        synchronized(this) { copy=new LinkedHashMap<>(snapshots); }
        Files.createDirectories(path.getParent());
        Path temporary=Files.createTempFile(path.getParent(),"climate-", ".tmp");
        try {
            try(var output=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                output.writeInt(MAGIC);output.writeInt(VERSION);write(output,copy);
            }
            try { Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
            catch(AtomicMoveNotSupportedException unsupported) { Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void write(DataOutputStream output,Map<Long,float[]> data) throws IOException {
        for(var entry:data.entrySet()) {
            output.writeLong(entry.getKey());
            for(float value:entry.getValue())output.writeFloat(value);
        }
    }

    @Override public synchronized void close() {
        if(closed)return;
        closed=true;
        if(path!=null)IO.execute(() -> {
            try { flush(); } catch(IOException failure) { LoggerFactory.getLogger("Roxy").warn("Unable to finish TFC climate sidecar",failure); }
        });
    }
}
