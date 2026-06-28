package com.flatide.propertee2.host;

import java.io.IOException;
import java.util.List;

/**
 * Host integration seam for environment + filesystem that the runtime gates through
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

    List<String> readLines(String path) throws IOException;    // UTF-8; trailing newline yields no empty line

    FileStat fileInfo(String path) throws IOException;         // throws if not found

    List<DirEntry> listDir(String path) throws IOException;    // sorted by name ascending

    void deleteFile(String path) throws IOException;           // throws if a directory or not found

    /** type is "file" or "dir"; size in bytes; modified in epoch millis. */
    record FileStat(String type, long size, long modified) {}

    record DirEntry(String name, String type, long size) {}
}
