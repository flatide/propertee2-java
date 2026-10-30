package com.flatide.propertee2.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.flatide.propertee2.core.ScriptParser;
import com.flatide.propertee2.host.PlatformProvider;
import com.flatide.propertee2.interpreter.BuiltinFunctions;
import com.flatide.propertee2.interpreter.ProperTeeInterpreter;
import com.flatide.propertee2.parser.ProperTeeParser;
import com.flatide.propertee2.scheduler.Scheduler;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * LIST_DIR / LIST_DIR_NAMES (spec v0.27.0, v0.28.0) — what fixture 140 cannot set up: symbolic
 * links, unreadable directories, messages that name the temporary path, and the same listing
 * through the v1 provider TeeBox runs on.
 */
class ListDirTest {

    private static final String ENTRIES = """
            loop e in UNWRAP(LIST_DIR(d)) do
                PRINT(e.name, e.type, e.size)
            end
            """;

    private Path dir;
    private final List<Path> locked = new ArrayList<>();

    @BeforeEach void setUp() throws IOException { dir = Files.createTempDirectory("propertee2-list-dir-"); }

    @AfterEach void tearDown() throws IOException {
        for (Path p : locked) Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwx------"));
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    /** a.txt, sub/, links to both, a link to nothing, and two links pointing at each other. */
    private void linkedTree() throws IOException {
        Files.writeString(dir.resolve("a.txt"), "hello");
        Files.createDirectory(dir.resolve("sub"));
        Files.createSymbolicLink(dir.resolve("to-a"), Path.of("a.txt"));
        Files.createSymbolicLink(dir.resolve("to-sub"), Path.of("sub"));
        Files.createSymbolicLink(dir.resolve("broken"), Path.of("missing/target"));
        Files.createSymbolicLink(dir.resolve("loop1"), Path.of("loop2"));
        Files.createSymbolicLink(dir.resolve("loop2"), Path.of("loop1"));
    }

    /** A directory nobody but root can read; skips the test where permissions are not enforced. */
    private Path lockedDir(String name) throws IOException { return lockedDir(name, "---------"); }

    /** A directory holding the file x, with {@code perms}, which must keep its owner from reading x. */
    private Path lockedDir(String name, String perms) throws IOException {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path p = Files.createDirectory(dir.resolve(name));
        Files.writeString(p.resolve("x"), "x");
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(perms));
        locked.add(p);
        assumeFalse(Files.isReadable(p.resolve("x")), "running as root");
        return p;
    }

    private String cli(String script) { return new Engine().run(script, Map.of("d", dir.toString())); }

    /** The same script through the v1 façade and the v1 DefaultPlatformProvider: TeeBox's path. */
    private String teebox(String script) {
        StringBuilder out = new StringBuilder();
        BuiltinFunctions.PrintFunction sink = args -> {
            for (Object a : args) out.append(a);
            out.append('\n');
        };
        BuiltinFunctions builtins = new BuiltinFunctions(sink, sink, "r", null,
                new com.flatide.propertee2.platform.DefaultPlatformProvider());
        ProperTeeParser.RootContext tree = ScriptParser.parse(script, new ArrayList<>());
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("d", dir.toString());
        ProperTeeInterpreter visitor = new ProperTeeInterpreter(props, sink, sink, 1000, "error", builtins);
        new Scheduler(visitor, null).run(visitor.createRootStepper(tree));
        return out.toString();
    }

    @Test void linksAreFollowedAndOneThatCannotBeIsListedAsLink() throws IOException {
        linkedTree();
        String expected = """
                a.txt file 5
                broken link 0
                loop1 link 0
                loop2 link 0
                sub dir 0
                to-a file 5
                to-sub dir 0
                """;
        assertEquals(expected, cli(ENTRIES));
        assertEquals(expected, teebox(ENTRIES));
    }

    @Test void aLinkToAnUnreadableTargetIsALinkAndAnUnreadableDirectoryFails() throws IOException {
        Path locked = lockedDir("locked");
        Files.createSymbolicLink(dir.resolve("to-x"), Path.of("locked/x"));
        String script = ENTRIES + "PRINT(LIST_DIR(d + \"/locked\").value)\nPRINT(LIST_DIR_NAMES(d + \"/locked\").value)\n";
        String expected = "locked dir 0\nto-x link 0\n"
                + "Permission denied: " + locked + "\n"
                + "Permission denied: " + locked + "\n";
        assertEquals(expected, cli(script));
        assertEquals(expected, teebox(script));
    }

    @Test void namesGiveEachEntrysOwnTypeAndFollowNoLink() throws IOException {
        linkedTree();
        String script = """
                loop e in UNWRAP(LIST_DIR_NAMES(d)) do
                    PRINT(e.name, e.type)
                end
                PRINT(UNWRAP(LIST_DIR_NAMES(d, "to-*")))
                """;
        String expected = """
                a.txt file
                broken link
                loop1 link
                loop2 link
                sub dir
                to-a link
                to-sub link
                [ { "name": 'to-a', "type": 'link' }, { "name": 'to-sub', "type": 'link' } ]
                """;
        assertEquals(expected, cli(script));
        assertEquals(expected, teebox(script));
    }

    @Test void aDirectoryWhoseEntriesCannotBeExaminedFails() throws IOException {
        Path listable = lockedDir("listable", "r--------");   // names readable, entries not
        String script = "PRINT(LIST_DIR(d + \"/listable\").value)\nPRINT(LIST_DIR_NAMES(d + \"/listable\").value)\n";
        String expected = "Permission denied: " + listable.resolve("x") + "\n"
                + "Permission denied: " + listable.resolve("x") + "\n";
        assertEquals(expected, cli(script));
        assertEquals(expected, teebox(script));
    }

    @Test void failuresNameTheReason() throws IOException {
        Files.writeString(dir.resolve("a.txt"), "hello");
        String script = """
                PRINT(LIST_DIR(d + "/missing").value)
                PRINT(LIST_DIR(d + "/a.txt").value)
                PRINT(LIST_DIR_NAMES(d + "/missing", "*.ice").value)
                """;
        String expected = "No such file or directory: " + dir.resolve("missing") + "\n"
                + "Not a directory: " + dir.resolve("a.txt") + "\n"
                + "No such file or directory: " + dir.resolve("missing") + "\n";
        assertEquals(expected, cli(script));
        assertEquals(expected, teebox(script));
        // the other file builtins share the message (NIO's own message was the bare path)
        assertEquals("No such file or directory: " + dir.resolve("missing") + "\n",
                cli("PRINT(READ_FILE(d + \"/missing\").value)\n"));
    }

    @Test void aPatternMustBeAString() {
        assertEquals("Runtime error: Runtime Error at line 1:1: LIST_DIR() requires a string argument\n", cli("LIST_DIR(d, 5)\n"));
        assertEquals("Runtime error: Runtime Error at line 1:1: LIST_DIR_NAMES() requires a string argument\n",
                cli("LIST_DIR_NAMES(d, {})\n"));
    }

    @Test void aProviderThatOnlyListsStillFiltersAndNames() {
        PlatformProvider provider = new OnlyListDir(List.of(
                new PlatformProvider.DirEntry("a.ice", "file", 1),
                new PlatformProvider.DirEntry("b.txt", "file", 2),
                new PlatformProvider.DirEntry("c.ice", "dir", 0)));
        String script = """
                PRINT(LEN(UNWRAP(LIST_DIR(d))))
                PRINT(UNWRAP(LIST_DIR(d, "*.ice")))
                PRINT(UNWRAP(LIST_DIR_NAMES(d)))
                PRINT(UNWRAP(LIST_DIR_NAMES(d, "b*")))
                """;
        assertEquals("""
                3
                [ { "name": 'a.ice', "type": 'file', "size": 1 }, { "name": 'c.ice', "type": 'dir', "size": 0 } ]
                [ { "name": 'a.ice', "type": 'file' }, { "name": 'b.txt', "type": 'file' }, { "name": 'c.ice', "type": 'dir' } ]
                [ { "name": 'b.txt', "type": 'file' } ]
                """, new Engine().run(script, Map.of("d", "/virtual"), provider));
    }

    /**
     * Spec v0.33.0: a file size or time past 32 bits is an integer, so sizes add up from 0 — before,
     * a total over 2 GiB of files under 2 GiB each was "Integer overflow" unless it began at 0.0.
     */
    @Test void sizesAndTimesPast32BitsAreIntegers() {
        long tb = 1L << 40;
        PlatformProvider provider = new OnlyListDir(List.of(
                new PlatformProvider.DirEntry("a.img", "file", 1_500_000_000),
                new PlatformProvider.DirEntry("b.img", "file", 1_500_000_000),
                new PlatformProvider.DirEntry("c.img", "file", 3 * tb))) {
            @Override public FileStat fileInfo(String path) { return new FileStat("file", 3 * tb, 1_792_834_328_647L); }
        };
        String script = """
                total = 0
                loop e in UNWRAP(LIST_DIR(d)) do
                    total = total + e.size
                end
                info = UNWRAP(FILE_INFO(d + "/c.img"))
                PRINT(total, info.size, info.modified, info.modified % 1000)
                """;
        assertEquals("3301534883328 3298534883328 1792834328647 647\n",
                new Engine().run(script, Map.of("d", "/virtual"), provider));
    }

    /** A host provider written before spec v0.27.0: it implements only the one-argument listDir. */
    private static class OnlyListDir implements PlatformProvider {
        private final List<DirEntry> entries;
        OnlyListDir(List<DirEntry> entries) { this.entries = entries; }
        @Override public List<DirEntry> listDir(String path) { return entries; }
        @Override public String env(String name) { return null; }
        @Override public boolean fileExists(String path) { return false; }
        @Override public void mkdir(String path) { throw new UnsupportedOperationException(); }
        @Override public void writeFile(String path, String content) { throw new UnsupportedOperationException(); }
        @Override public void appendFile(String path, String content) { throw new UnsupportedOperationException(); }
        @Override public List<String> readLines(String path, long start, long count) { throw new UnsupportedOperationException(); }
        @Override public FileStat fileInfo(String path) { throw new UnsupportedOperationException(); }
        @Override public void deleteFile(String path) { throw new UnsupportedOperationException(); }
        @Override public HttpResponse httpRequest(String method, String url, Map<String, String> headers, String body,
                                                  int timeoutMs) { throw new UnsupportedOperationException(); }
    }
}
