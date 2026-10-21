package com.flatide.propertee2.host;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Host integration seam for environment, filesystem, and HTTP that the runtime gates through
 * {@code Coop.blocking} (design §3.1). Implementations may block; the interpreter always invokes
 * them off the baton. The builtin layer wraps these in the v1 {@code Result} shape and does
 * argument validation — these methods do the raw work and throw {@link IOException} on failure.
 */
public interface PlatformProvider {

    /** Environment variable value, or {@code null} if unset. */
    String env(String name);

    // ---- filesystem -------------------------------------------------------

    boolean fileExists(String path);

    void mkdir(String path) throws IOException;                 // creates parents; no error if present

    void writeFile(String path, String content) throws IOException;   // overwrite

    void appendFile(String path, String content) throws IOException;

    /**
     * Read a WINDOW of lines: skip {@code start - 1} (1-based start), then return up to {@code count}.
     * Streams so a large file is not loaded whole (LANGUAGE.md §File I/O large-file iteration).
     * Use {@code count = Long.MAX_VALUE} for "to end of file". UTF-8; a trailing newline yields no empty line.
     */
    List<String> readLines(String path, long start, long count) throws IOException;

    /**
     * Read a whole file as UTF-8 (READ_JSON_FILE, spec v0.18.0). The default composes
     * {@link #readLines} so existing provider implementations keep working — newline flavor is
     * normalized to {@code \n}, which is lossless for JSON. Providers with direct file access
     * should override with a real whole-file read (as {@code DefaultPlatformProvider} does).
     */
    default String readFile(String path) throws IOException {
        return String.join("\n", readLines(path, 1, Long.MAX_VALUE));
    }

    FileStat fileInfo(String path) throws IOException;         // throws if not found

    List<DirEntry> listDir(String path) throws IOException;    // sorted by name ascending

    /**
     * LIST_DIR with a name pattern (spec v0.27.0): the entries whose names match the glob
     * {@code pattern}, or all of them when it is null. The default filters {@link #listDir(String)}
     * so existing providers keep working; {@code DefaultPlatformProvider} matches while it reads.
     * LIST_DIR without a pattern still calls {@link #listDir(String)}; a provider that restricts
     * paths there overrides this and {@link #listDirNames} too, as it does {@link #readAt}.
     */
    default List<DirEntry> listDir(String path, String pattern) throws IOException {
        List<DirEntry> all = listDir(path);
        if (pattern == null) return all;
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        List<DirEntry> out = new ArrayList<>();
        for (DirEntry e : all) {
            if (matcher.matches(Path.of(e.name()))) out.add(e);
        }
        return out;
    }

    /**
     * LIST_DIR_NAMES (spec v0.27.0): the names alone, sorted. The default takes them from
     * {@link #listDir(String, String)}; a provider with direct file access overrides it to skip
     * examining each entry, which is what makes a large directory of links slow to list.
     */
    default List<String> listDirNames(String path, String pattern) throws IOException {
        List<String> out = new ArrayList<>();
        for (DirEntry e : listDir(path, pattern)) out.add(e.name());
        return out;
    }

    void deleteFile(String path) throws IOException;           // throws if a directory or not found

    /**
     * Up to {@code len} bytes of the file at {@code offset} into {@code buf} (FILE_LINES, spec
     * v0.26.0): the count read — fewer than {@code len} only at end of file — or -1 when
     * {@code offset} is at or past the end. The file is opened and closed inside the call, so a
     * loop suspended between chunks holds no descriptor. The default reads the file directly; a
     * provider that virtualizes or restricts files overrides it ({@link #fileExists} and
     * {@link #fileInfo}, which FILE_LINES also uses, likewise).
     */
    default int readAt(String path, long offset, byte[] buf, int len) throws IOException {
        try (FileChannel ch = FileChannel.open(Path.of(path), StandardOpenOption.READ)) {
            ByteBuffer bb = ByteBuffer.wrap(buf, 0, len);
            while (bb.hasRemaining()) {
                if (ch.read(bb, offset + bb.position()) < 0) break;
            }
            return bb.position() == 0 ? -1 : bb.position();
        }
    }

    /**
     * Perform an HTTP request. Hosts may restrict URLs/headers or throw on transport failures.
     * Any HTTP status is returned normally; only transport/setup failures should throw.
     */
    HttpResponse httpRequest(String method, String url, Map<String, String> headers, String body, int timeoutMs)
            throws IOException;

    /** type is "file" or "dir"; size in bytes; modified in epoch millis. */
    record FileStat(String type, long size, long modified) {}

    /** type is "file", "dir" or "link" (a link that cannot be followed, spec v0.27.0); size is 0 for a dir or link. */
    record DirEntry(String name, String type, long size) {}

    record HttpResponse(int status, String body, Map<String, String> headers) {}
}
