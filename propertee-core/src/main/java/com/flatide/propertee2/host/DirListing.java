package com.flatide.propertee2.host;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * LIST_DIR / LIST_DIR_NAMES over the real file system (spec v0.27.0, v0.28.0). Both default
 * providers — this package's and the v1 {@code platform.DefaultPlatformProvider} that TeeBox
 * extends — list through here, so a directory reads the same under the CLI and under TeeBox.
 */
public final class DirListing {

    private DirListing() { }

    /**
     * The entries of {@code dir} whose names match the glob {@code pattern} (all when null), sorted
     * by name. Each entry is examined once, following a symbolic link: a link to a file is a "file"
     * with that file's size, a link to a directory a "dir". A link that cannot be followed (its
     * target is missing or inaccessible, or links loop) is a "link" of size 0 instead of failing the
     * listing, and an entry removed while the directory is read is left out.
     */
    public static List<PlatformProvider.DirEntry> entries(String dir, String pattern) throws IOException {
        List<PlatformProvider.DirEntry> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = open(dir, pattern)) {
            for (Path p : stream) {
                PlatformProvider.DirEntry e = entry(p);
                if (e != null) out.add(e);
            }
        } catch (DirectoryIteratorException e) {
            throw e.getCause();
        }
        out.sort(Comparator.comparing(PlatformProvider.DirEntry::name));
        return out;
    }

    /**
     * Each entry's name and its own type, sorted by name (LIST_DIR_NAMES, spec v0.28.0). An entry is
     * examined without following it — a symbolic link is a "link" whatever it points to, even
     * nothing — so what the links point to is never read, which is what makes {@link #entries} slow
     * on a directory full of links. Sizes are not read (0); an entry removed while the directory is
     * read is left out.
     */
    public static List<PlatformProvider.DirEntry> names(String dir, String pattern) throws IOException {
        List<PlatformProvider.DirEntry> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = open(dir, pattern)) {
            for (Path p : stream) {
                BasicFileAttributes own;
                try {
                    own = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (NoSuchFileException gone) {
                    continue;
                }
                String type = own.isSymbolicLink() ? "link" : own.isDirectory() ? "dir" : "file";
                out.add(new PlatformProvider.DirEntry(p.getFileName().toString(), type, 0));
            }
        } catch (DirectoryIteratorException e) {
            throw e.getCause();
        }
        out.sort(Comparator.comparing(PlatformProvider.DirEntry::name));
        return out;
    }

    private static DirectoryStream<Path> open(String dir, String pattern) throws IOException {
        Path d = Path.of(dir);
        return pattern == null ? Files.newDirectoryStream(d) : Files.newDirectoryStream(d, pattern);
    }

    private static PlatformProvider.DirEntry entry(Path p) throws IOException {
        String name = p.getFileName().toString();
        BasicFileAttributes target;
        try {
            target = Files.readAttributes(p, BasicFileAttributes.class);
        } catch (IOException e) {
            BasicFileAttributes own;
            try {
                own = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException gone) {
                return null;
            }
            if (own.isSymbolicLink()) return new PlatformProvider.DirEntry(name, "link", 0);
            throw e;
        }
        return target.isDirectory()
                ? new PlatformProvider.DirEntry(name, "dir", 0)
                : new PlatformProvider.DirEntry(name, "file", target.size());
    }
}
