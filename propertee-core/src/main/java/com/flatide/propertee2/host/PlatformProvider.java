package com.flatide.propertee2.host;

import java.io.IOException;
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

    FileStat fileInfo(String path) throws IOException;         // throws if not found

    List<DirEntry> listDir(String path) throws IOException;    // sorted by name ascending

    void deleteFile(String path) throws IOException;           // throws if a directory or not found

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
