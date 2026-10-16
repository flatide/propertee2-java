package com.flatide.propertee2.host;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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

    record DirEntry(String name, String type, long size) {}

    record HttpResponse(int status, String body, Map<String, String> headers) {}
}
