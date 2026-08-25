package com.flatide.propertee2.cli;

import com.flatide.propertee2.module.ModuleRequest;
import com.flatide.propertee2.module.ModuleResolutionException;
import com.flatide.propertee2.module.ModuleResolver;
import com.flatide.propertee2.module.ModuleSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Filesystem module lookup for the CLI, rooted at one explicitly contained directory. */
final class FileModuleResolver implements ModuleResolver {
    private final Path root;

    FileModuleResolver(Path root) {
        try {
            this.root = root.toAbsolutePath().normalize().toRealPath();
        } catch (IOException e) {
            throw new ModuleResolutionException("Cannot open module root '" + root + "': " + e.getMessage(), e);
        }
        if (!Files.isDirectory(this.root)) {
            throw new ModuleResolutionException("Module root is not a directory: " + this.root);
        }
    }

    @Override public ModuleSource resolve(ModuleRequest request) {
        String[] segments = request.moduleId().split("\\.");
        Path directory = root;
        for (int i = 0; i < segments.length - 1; i++) directory = directory.resolve(segments[i]);
        String base = segments[segments.length - 1];

        if (request.version() != null) {
            Path selected = directory.resolve(base + "." + request.version() + ".tee");
            return read(request.moduleId(), request.version(), selected, request.displayName());
        }

        VersionedFile newest = newestVersion(directory, base);
        if (newest != null) return read(request.moduleId(), newest.version, newest.path, request.displayName());
        return read(request.moduleId(), null, directory.resolve(base + ".tee"), request.displayName());
    }

    private VersionedFile newestVersion(Path directory, String base) {
        if (!Files.isDirectory(directory)) return null;
        Pattern pattern = Pattern.compile(Pattern.quote(base) + "\\.([1-9][0-9]*)\\.tee");
        try (var files = Files.list(directory)) {
            return files.map(path -> {
                        Matcher m = pattern.matcher(path.getFileName().toString());
                        if (!m.matches()) return null;
                        try {
                            return new VersionedFile(Integer.parseInt(m.group(1)), path);
                        } catch (NumberFormatException ignored) {
                            return null;
                        }
                    })
                    .filter(java.util.Objects::nonNull)
                    .max(Comparator.comparingInt(VersionedFile::version))
                    .orElse(null);
        } catch (IOException e) {
            throw new ModuleResolutionException("Cannot scan module directory '" + directory
                    + "': " + e.getMessage(), e);
        }
    }

    private ModuleSource read(String moduleId, Integer version, Path candidate, String requested) {
        final Path real;
        try {
            real = candidate.toRealPath();
        } catch (IOException e) {
            throw new ModuleResolutionException("Module '" + requested + "' was not found");
        }
        if (!real.startsWith(root)) {
            throw new ModuleResolutionException("Module '" + requested + "' escapes module root");
        }
        if (!Files.isRegularFile(real)) {
            throw new ModuleResolutionException("Module '" + requested + "' is not a regular file");
        }
        try {
            return new ModuleSource(moduleId, version, real.toString(),
                    Files.readString(real, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ModuleResolutionException("Cannot read module '" + requested + "': "
                    + e.getMessage(), e);
        }
    }

    private record VersionedFile(int version, Path path) {}
}
