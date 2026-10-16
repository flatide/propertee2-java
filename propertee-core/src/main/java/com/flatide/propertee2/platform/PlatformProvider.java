package com.flatide.propertee2.platform;

import java.util.List;
import java.util.Map;

/**
 * Host-provided capability interface for OS resource access.
 * <p>
 * Pure language functions (string, map, JSON, type) are always available.
 * ENV and file I/O require a host to provide a PlatformProvider implementation.
 * Without one, these functions return "unsupported" errors.
 * <p>
 * Hosts can implement this interface to control access policies:
 * path restrictions, read-only mode, environment variable filtering, etc.
 */
public interface PlatformProvider {

    // --- Environment ---

    /** Returns environment variable value, or null if not set. */
    String getEnv(String name);

    // --- File query ---

    boolean fileExists(String path);

    FileInfo fileInfo(String path) ;

    List<FileEntry> listDir(String path) ;

    // --- File read ---

    /** Read lines from file. start is 1-based, count limits lines returned. */
    List<String> readLines(String path, int start, int count) ;

    /**
     * Up to {@code len} bytes of the file at {@code offset} into {@code buf} (FILE_LINES, spec
     * v0.26.0): the count read — fewer than {@code len} only at end of file — or -1 at or past the
     * end. Opened and closed inside the call. The default reads the file directly, failing with the
     * v1-style unchecked "Failed to read file: ..."; a provider that restricts paths overrides it.
     */
    default int readAt(String path, long offset, byte[] buf, int len) {
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(
                java.nio.file.Path.of(path), java.nio.file.StandardOpenOption.READ)) {
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(buf, 0, len);
            while (bb.hasRemaining()) {
                if (ch.read(bb, offset + bb.position()) < 0) break;
            }
            return bb.position() == 0 ? -1 : bb.position();
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to read file: " + e.getMessage(), e);
        }
    }

    // --- File write ---

    void writeFile(String path, String content) ;

    void writeLines(String path, List<String> lines) ;

    void appendFile(String path, String content) ;

    // --- File management ---

    void mkdir(String path) ;

    void deleteFile(String path) ;

    // --- HTTP ---

    /**
     * Perform an HTTP request. {@code headers} and {@code body} may be null/empty;
     * {@code timeoutMs <= 0} uses a host default. Returns the response (any HTTP status,
     * including 4xx/5xx). Throws on a transport-level failure (bad URL, DNS, connect, timeout).
     */
    HttpResponse httpRequest(String method, String url, Map<String, String> headers, String body, int timeoutMs);

    // --- Data classes ---

    class HttpResponse {
        public final int status;
        public final String body;
        public final Map<String, String> headers;

        public HttpResponse(int status, String body, Map<String, String> headers) {
            this.status = status;
            this.body = body;
            this.headers = headers;
        }
    }

    class FileInfo {
        public final String type;  // "file" or "dir"
        public final long size;
        public final long modified;

        public FileInfo(String type, long size, long modified) {
            this.type = type;
            this.size = size;
            this.modified = modified;
        }
    }

    class FileEntry {
        public final String name;
        public final String type;  // "file" or "dir"
        public final long size;

        public FileEntry(String name, String type, long size) {
            this.name = name;
            this.type = type;
            this.size = size;
        }
    }
}
