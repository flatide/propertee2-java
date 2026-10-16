package com.flatide.propertee2.value;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.zip.CRC32C;

/**
 * The array {@code FILE_LINES(path)} returns (spec v0.26.0). To a script it is an ordinary array
 * — TYPE_OF "array", the same display, JSON, equality, loops and copies — but it holds only a
 * recipe (the path and the file's identity at the call) and reads the file when it is used:
 * <ul>
 *   <li>a loop streams it ({@link #lines}) chunk by chunk, keeping only the current line;</li>
 *   <li>{@code LEN} counts the lines without keeping them (once; shared by copies);</li>
 *   <li>a copy (assignment, argument, return, thread snapshot) shares the recipe;</li>
 *   <li>anything else loads the lines into a plain list first, if the file is not larger than the
 *       host's whole-use limit; from then on the value is an ordinary array.</li>
 * </ul>
 * The value is the file as it was at the call: bytes appended later are not part of it, and a
 * file truncated, rewritten or deleted since fails the next read instead of yielding other
 * content — checked before every chunk and after the last, against the size, modification time
 * and a CRC of the first and last {@link #SAMPLE} bytes seen at the call.
 *
 * <p>Concurrency: the recipe is immutable apart from its caches and may be shared by every thread
 * of a multi block. All file I/O goes through {@link Io#blocking}, which the interpreter uses to
 * release the scheduler baton around each read when that is safe; loading is one such unit under
 * this value's monitor, which is only ever taken off the baton or by a thread that never waits
 * for the baton while holding it, so it cannot deadlock with the scheduler.
 */
public final class LazyLines extends AbstractList<Object> {

    /** Whole-use limit in bytes: a larger file can only be gone through with a loop (host-configurable). */
    public static final long DEFAULT_LOAD_LIMIT = 64L * 1024 * 1024;
    /** Longest line in bytes (host-configurable). */
    public static final int DEFAULT_MAX_LINE = 16 * 1024 * 1024;

    static final int SAMPLE = 4096;

    /** File work that may fail. */
    @FunctionalInterface
    public interface Work<T> {
        T call() throws IOException;
    }

    /** Runs file work; the interpreter's gate releases the scheduler baton around it when that is safe. */
    public interface Gate {
        <T> T run(Work<T> work) throws IOException;

        /** No scheduler: run the work in place. */
        Gate DIRECT = new Gate() {
            @Override public <T> T run(Work<T> work) throws IOException { return work.call(); }
        };
    }

    /** What a path is right now, as the host's platform provider reports it. */
    public record Stat(boolean exists, boolean directory, long size, long modified) {
        public static final Stat MISSING = new Stat(false, false, 0, 0);
    }

    /** How the array reaches its file: the platform provider, the gate, this run's rewrite count, the limits. */
    public interface Io {
        Stat stat(String path) throws IOException;

        /** Up to {@code len} bytes at {@code offset} into {@code buf}: the count, or -1 at or past the end. */
        int readAt(String path, long offset, byte[] buf, int len) throws IOException;

        <T> T blocking(Work<T> work) throws IOException;

        /** How often this run has rewritten the path (WRITE_FILE, WRITE_LINES, WRITE_JSON_FILE, DELETE_FILE). */
        long rewrites(String path);

        long loadLimit();

        int maxLine();
    }

    /** The recipe — the file's identity at the call. Shared by copies and threads; only its caches change. */
    private static final class Source {
        final String path;
        final Io io;
        final long size;
        final long modified;
        final long sample;                 // CRC of the first and last SAMPLE bytes at the call
        final long rewritesAtCall;
        final long loadLimit;
        final int maxLine;
        volatile int lineCount = -1;
        // The last (size, modified) pair whose head and tail were found intact: a live log grows
        // between chunks and each new pair is sampled once; a static file is never sampled again.
        volatile long verifiedSize;
        volatile long verifiedModified;

        Source(String path, Io io, Stat stat) throws IOException {
            this.path = path;
            this.io = io;
            this.size = stat.size();
            this.modified = stat.modified();
            this.rewritesAtCall = io.rewrites(path);
            this.loadLimit = io.loadLimit();
            this.maxLine = io.maxLine();
            this.sample = sample(path, io, size);
            this.verifiedSize = size;
            this.verifiedModified = modified;
        }

        /** Does the file still hold the bytes of the call? Throws with the reason if not. */
        void check() throws IOException {
            if (io.rewrites(path) != rewritesAtCall) {
                throw new IOException("it was rewritten after FILE_LINES() was called — call FILE_LINES() again");
            }
            Stat now = io.stat(path);
            if (!now.exists()) throw new IOException("it was deleted after FILE_LINES() was called");
            if (now.directory()) throw modified();
            if (now.size() < size) {
                throw new IOException("it was truncated after FILE_LINES() was called — call FILE_LINES() again");
            }
            if (now.size() == size) {
                if (now.modified() != modified) throw modified();
                return;
            }
            // Longer: an append leaves the original bytes' head and tail intact; a rewrite does not.
            if (now.size() != verifiedSize || now.modified() != verifiedModified) {
                if (sample(path, io, size) != sample) throw modified();
                verifiedSize = now.size();
                verifiedModified = now.modified();
            }
        }

        private static IOException modified() {
            return new IOException("it was modified after FILE_LINES() was called — call FILE_LINES() again");
        }

        /** One chunk: check, then read (the provider opens and closes the file inside readAt). */
        int chunk(long offset, byte[] buf, int len) throws IOException {
            check();
            return io.readAt(path, offset, buf, len);
        }
    }

    private static long sample(String path, Io io, long size) throws IOException {
        int head = (int) Math.min(SAMPLE, size);
        int tail = (int) Math.min(SAMPLE, size - head);
        byte[] bytes = new byte[head + tail];
        readFully(path, io, 0, bytes, 0, head);
        readFully(path, io, size - tail, bytes, head, tail);
        CRC32C crc = new CRC32C();
        crc.update(bytes);
        return crc.getValue();
    }

    private static void readFully(String path, Io io, long offset, byte[] into, int at, int n) throws IOException {
        int done = 0;
        while (done < n) {
            byte[] part = new byte[n - done];
            int r = io.readAt(path, offset + done, part, part.length);
            if (r < 0) throw new IOException("the file was truncated while it was being read");
            System.arraycopy(part, 0, into, at + done, r);
            done += r;
        }
    }

    /** FILE_LINES(path): check the file now — a missing or unreadable file fails at the call — and read it later. */
    public static LazyLines of(String path, Io io) throws IOException {
        Stat stat = io.stat(path);
        if (!stat.exists()) throw new IOException("no such file");
        if (stat.directory()) throw new IOException("it is a directory");
        try {
            return new LazyLines(new Source(path, io, stat));
        } catch (AccessDeniedException e) {
            throw new IOException("permission denied");
        }
    }

    private final Source source;
    private volatile ArrayList<Object> loaded;   // this value only: set once the whole array is needed

    private LazyLines(Source source) {
        this.source = source;
    }

    /** True once the lines are in memory — from then on this is an ordinary array (a loop iterates it). */
    public boolean isLoaded() { return loaded != null; }

    public String path() { return source.path; }

    /** The copy that value semantics asks for: the recipe shared until something loads it, a real copy after. */
    public Object copy() {
        ArrayList<Object> l = loaded;
        if (l == null) return new LazyLines(source);
        return new ArrayList<Object>(l);                // strings: nothing deeper to copy
    }

    /**
     * One pass for a loop: the file is checked first, so a file changed since the call fails at the
     * loop rather than midway, then read chunk by chunk through the gate.
     */
    public LineReader lines() throws IOException {
        source.io.blocking(() -> { source.check(); return null; });
        return new LineReader((off, buf, len) -> source.io.blocking(() -> source.chunk(off, buf, len)),
                source.size, source.maxLine, () -> source.io.blocking(() -> { source.check(); return null; }));
    }

    /** One pass inside an already-gated unit. */
    private LineReader direct() {
        return new LineReader(source::chunk, source.size, source.maxLine, source::check);
    }

    private TeeError cannotRead(IOException e) {
        return new TeeError("FILE_LINES() cannot read '" + source.path + "': " + e.getMessage());
    }

    private ArrayList<Object> load() {
        ArrayList<Object> l = loaded;
        if (l != null) return l;
        if (source.size > source.loadLimit) {
            throw new TeeError("FILE_LINES() '" + source.path + "' is too large to use as a whole ("
                    + human(source.size) + ", the limit is " + human(source.loadLimit)
                    + ") — go through it with a loop: loop line in ... do");
        }
        try {
            return source.io.blocking(() -> {
                synchronized (this) {
                    if (loaded == null) {
                        ArrayList<Object> lines = new ArrayList<>();
                        LineReader r = direct();
                        for (String s = r.next(); s != null; s = r.next()) lines.add(s);
                        source.lineCount = lines.size();
                        loaded = lines;
                    }
                    return loaded;
                }
            });
        } catch (IOException e) {
            throw cannotRead(e);
        }
    }

    static String human(long bytes) {
        if (bytes >= 1L << 20) return (bytes >> 20) + " MB";
        if (bytes >= 1L << 10) return (bytes >> 10) + " KB";
        return bytes + " bytes";
    }

    @Override
    public int size() {
        ArrayList<Object> l = loaded;
        if (l != null) return l.size();
        int n = source.lineCount;
        if (n >= 0) return n;
        try {
            int count = source.io.blocking(() -> {
                int c = 0;
                LineReader r = direct();
                while (r.next() != null) c++;
                return c;
            });
            source.lineCount = count;
            return count;
        } catch (IOException e) {
            throw cannotRead(e);
        }
    }

    @Override public Object get(int index) { return load().get(index); }
    @Override public Object set(int index, Object v) { return load().set(index, v); }
    @Override public void add(int index, Object v) { load().add(index, v); }
    @Override public Object remove(int index) { return load().remove(index); }
    @Override public Iterator<Object> iterator() { return load().iterator(); }
    @Override public Object[] toArray() { return load().toArray(); }
    @Override public <T> T[] toArray(T[] a) { return load().toArray(a); }

    @Override
    public String toString() {
        ArrayList<Object> l = loaded;
        return l != null ? l.toString() : "[lines of '" + source.path + "']";
    }
}
