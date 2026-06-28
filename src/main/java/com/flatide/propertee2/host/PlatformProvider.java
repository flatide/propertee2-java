package com.flatide.propertee2.host;

/**
 * Host integration seam for environment + I/O that the runtime gates through {@code Coop.blocking}
 * (design §3.1). Implementations may block; the interpreter always invokes them off the baton.
 * Extended in PC-3 with file operations.
 */
public interface PlatformProvider {

    /** Environment variable value, or {@code null} if unset. */
    String env(String name);
}
