package com.flatide.propertee2.host;

/** Default host integration backed by the real JVM environment and filesystem. */
public final class DefaultPlatformProvider implements PlatformProvider {

    @Override
    public String env(String name) {
        return System.getenv(name);
    }
}
