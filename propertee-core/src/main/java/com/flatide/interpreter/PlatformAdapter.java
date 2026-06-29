package com.flatide.interpreter;

import com.flatide.propertee2.host.PlatformProvider.DirEntry;
import com.flatide.propertee2.host.PlatformProvider.FileStat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Bridges a v1 {@link com.flatide.platform.PlatformProvider} (what TeeBox supplies) to the engine's
 * {@code com.flatide.propertee2.host.PlatformProvider} so the ENV / file-I/O builtins use the host's
 * platform. Two impedance mismatches are handled here:
 * <ul>
 *   <li>v1 takes {@code int} line offsets; the engine uses {@code long} (and passes {@code Long.MAX_VALUE}
 *       for "to end of file"). A naive cast wraps to -1, which v1 reads as "zero lines" — so the cast is
 *       <em>saturated</em> to {@code Integer.MAX_VALUE}.</li>
 *   <li>v1 platform impls signal file errors with unchecked {@code RuntimeException} ("File not found:",
 *       "Failed to read file:", ...). The engine's file builtins only turn {@code IOException} into a
 *       script-level {@code Result.error}, so each file op translates {@code RuntimeException -> IOException}
 *       here (otherwise a missing file would fail the whole run instead of the builtin).</li>
 * </ul>
 */
final class PlatformAdapter implements com.flatide.propertee2.host.PlatformProvider {

    private final com.flatide.platform.PlatformProvider v1;

    PlatformAdapter(com.flatide.platform.PlatformProvider v1) { this.v1 = v1; }

    // env / fileExists never signal I/O failure in v1 (return a value), so they pass through untranslated.
    @Override public String env(String name) { return v1.getEnv(name); }
    @Override public boolean fileExists(String path) { return v1.fileExists(path); }

    @Override public void mkdir(String path) throws IOException { ioVoid(() -> v1.mkdir(path)); }
    @Override public void writeFile(String path, String content) throws IOException { ioVoid(() -> v1.writeFile(path, content)); }
    @Override public void appendFile(String path, String content) throws IOException { ioVoid(() -> v1.appendFile(path, content)); }
    @Override public void deleteFile(String path) throws IOException { ioVoid(() -> v1.deleteFile(path)); }

    @Override public List<String> readLines(String path, long start, long count) throws IOException {
        return io(() -> v1.readLines(path, saturate(start), saturate(count)));
    }

    @Override public FileStat fileInfo(String path) throws IOException {
        return io(() -> {
            com.flatide.platform.PlatformProvider.FileInfo fi = v1.fileInfo(path);
            return new FileStat(fi.type, fi.size, fi.modified);
        });
    }

    @Override public List<DirEntry> listDir(String path) throws IOException {
        return io(() -> {
            List<DirEntry> out = new ArrayList<>();
            for (com.flatide.platform.PlatformProvider.FileEntry e : v1.listDir(path)) {
                out.add(new DirEntry(e.name, e.type, e.size));
            }
            return out;
        });
    }

    /** Saturating long->int so "to end of file" (Long.MAX_VALUE) stays a large positive count, not -1. */
    private static int saturate(long v) {
        if (v > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (v < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) v;
    }

    @FunctionalInterface private interface IoSupplier<T> { T get(); }
    @FunctionalInterface private interface IoRunnable { void run(); }

    private static <T> T io(IoSupplier<T> op) throws IOException {
        try {
            return op.get();
        } catch (RuntimeException e) {                 // v1 file errors are RuntimeException -> Result.error
            throw new IOException(e.getMessage(), e);
        }
    }

    private static void ioVoid(IoRunnable op) throws IOException {
        try {
            op.run();
        } catch (RuntimeException e) {
            throw new IOException(e.getMessage(), e);
        }
    }
}
