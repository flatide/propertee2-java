package com.flatide.propertee2.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Default host integration backed by the real JVM environment and filesystem (NIO). */
public final class DefaultPlatformProvider implements PlatformProvider {

    @Override
    public String env(String name) {
        return System.getenv(name);
    }

    @Override
    public boolean fileExists(String path) {
        return Files.exists(Path.of(path));
    }

    @Override
    public void mkdir(String path) throws IOException {
        Files.createDirectories(Path.of(path));
    }

    @Override
    public void writeFile(String path, String content) throws IOException {
        Files.writeString(Path.of(path), content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    @Override
    public void appendFile(String path, String content) throws IOException {
        Files.writeString(Path.of(path), content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @Override
    public List<String> readLines(String path) throws IOException {
        return Files.readAllLines(Path.of(path), StandardCharsets.UTF_8);
    }

    @Override
    public FileStat fileInfo(String path) throws IOException {
        Path p = Path.of(path);
        if (!Files.exists(p)) throw new NoSuchFileException(path);
        boolean dir = Files.isDirectory(p);
        long size = dir ? 0 : Files.size(p);
        long modified = Files.getLastModifiedTime(p).toMillis();
        return new FileStat(dir ? "dir" : "file", size, modified);
    }

    @Override
    public List<DirEntry> listDir(String path) throws IOException {
        List<DirEntry> entries = new ArrayList<>();
        try (Stream<Path> list = Files.list(Path.of(path))) {
            for (Path p : (Iterable<Path>) list::iterator) {
                boolean dir = Files.isDirectory(p);
                long size = dir ? 0 : Files.size(p);
                entries.add(new DirEntry(p.getFileName().toString(), dir ? "dir" : "file", size));
            }
        }
        entries.sort(Comparator.comparing(DirEntry::name));
        return entries;
    }

    @Override
    public void deleteFile(String path) throws IOException {
        Path p = Path.of(path);
        if (Files.isDirectory(p)) throw new IOException("is a directory: " + path);
        Files.delete(p);   // throws NoSuchFileException if absent
    }
}
