package com.flatide.propertee2.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flatide.propertee2.builtin.Builtins;
import com.flatide.propertee2.host.PlatformProvider;
import com.flatide.propertee2.runtime.TypeChecker;
import com.flatide.propertee2.value.LazyLines;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * FILE_LINES (spec v0.26.0) — the corners fixture 138 cannot pin byte-stably (the messages name
 * the temporary path) and the host-side contracts: the value is the file at the call, the limits,
 * the host boundaries, and one value shared by many threads.
 */
class FileLinesTest {

    private Path dir;

    @BeforeEach void setUp() throws IOException { dir = Files.createTempDirectory("propertee2-file-lines-"); }

    @AfterEach void tearDown() throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    /** A file whose modification time is safely in the past, so a later write is a visible change. */
    private Path file(String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, content);
        Files.setLastModifiedTime(p, FileTime.fromMillis(System.currentTimeMillis() - 5_000));
        return p;
    }

    private String run(Engine e, String script, Path f) { return e.run(script, Map.of("f", f.toString())); }

    private String run(String script, Path f) { return run(new Engine(), script, f); }

    private static String cannotRead(Path f, String reason) {
        return "FILE_LINES() cannot read '" + f + "': " + reason;
    }

    @Test void theValueIsTheFileAtTheCall() throws IOException {
        Path f = file("a.txt", "one\ntwo\n");
        // an append is not part of the old array — a new call sees it (three lines by then);
        // appending inside the loop does not prolong the loop
        assertEquals("2 one,two\n3\n3\n", run(
                "x = FILE_LINES(f)\nAPPEND_FILE(f, \"three\\n\")\nPRINT(LEN(x), JOIN(x, \",\"))\n"
                + "PRINT(LEN(FILE_LINES(f)))\nn = 0\nloop l in FILE_LINES(f) infinite do\n"
                + "    APPEND_FILE(f, l + \"+\\n\")\n    n = n + 1\nend\nPRINT(n)\n", f));

        // the script's own rewrite — same size, so only the per-path counter can tell
        f = file("b.txt", "one\ntwo\n");
        assertEquals("Runtime error: Runtime Error at line 3:0: "
                + cannotRead(f, "it was rewritten after FILE_LINES() was called — call FILE_LINES() again") + "\n",
                run("x = FILE_LINES(f)\nWRITE_FILE(f, \"ONE\\nTWO\\n\")\nloop l in x do\n    PRINT(l)\nend\n", f));

        // changes by someone else (host externals here): a truncation, a deletion, a rewrite in
        // place that keeps the size, one that grows the file — and a plain append, which is fine
        Path g = file("c.txt", "one\ntwo\n");
        Engine e = new Engine();
        e.registerExternal("CHANGE", a -> {
            try {
                switch ((String) a.get(0)) {
                    case "truncate" -> Files.writeString(g, "o\n");
                    case "delete" -> Files.delete(g);
                    case "same" -> Files.writeString(g, "ONE\nTWO\n");
                    case "grow" -> Files.writeString(g, "ONE\nTWO\nTHREE\n");
                    case "append" -> Files.writeString(g, "one\ntwo\nthree\n");
                    default -> throw new IllegalArgumentException(a.get(0).toString());
                }
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
            return 1;
        });
        for (String[] c : new String[][] {
                {"truncate", "it was truncated after FILE_LINES() was called — call FILE_LINES() again"},
                {"delete", "it was deleted after FILE_LINES() was called"},
                {"same", "it was modified after FILE_LINES() was called — call FILE_LINES() again"},
                {"grow", "it was modified after FILE_LINES() was called — call FILE_LINES() again"}}) {
            file("c.txt", "one\ntwo\n");
            // through the loop (positioned) and through LEN (a builtin: positionless)
            assertEquals("Runtime error: Runtime Error at line 3:0: " + cannotRead(g, c[1]) + "\n",
                    run(e, "x = FILE_LINES(f)\nCHANGE(\"" + c[0] + "\")\nloop l in x do\n    PRINT(l)\nend\n", g), c[0]);
            file("c.txt", "one\ntwo\n");
            assertEquals("Runtime error: Runtime Error: " + cannotRead(g, c[1]) + "\n",
                    run(e, "x = FILE_LINES(f)\nCHANGE(\"" + c[0] + "\")\nPRINT(LEN(x))\n", g), c[0]);
        }
        file("c.txt", "one\ntwo\n");
        assertEquals("one\ntwo\n2\n", run(e, "x = FILE_LINES(f)\nCHANGE(\"append\")\nloop l in x do\n    PRINT(l)\nend\nPRINT(LEN(x))\n", g));
    }

    @Test void contentErrorsFailWhenTheLineIsReached() throws IOException {
        Path bad = dir.resolve("bad.txt");
        Files.write(bad, "ok\nÿ\nnever\n".getBytes(StandardCharsets.ISO_8859_1));   // 0xFF is not UTF-8
        assertEquals("1 ok\nRuntime error: Runtime Error at line 1:0: " + cannotRead(bad, "line 2 is not valid UTF-8") + "\n",
                run("loop n, l in FILE_LINES(f) do\n    PRINT(n, l)\nend\n", bad));

        Path lng = file("long.txt", "short\n" + "x".repeat(9) + "\nafter\n");
        Engine e = new Engine().setFileLinesLimits(0, 8);
        assertEquals("1 short\nRuntime error: Runtime Error at line 1:0: " + cannotRead(lng, "line 2 is longer than 8 bytes") + "\n",
                run(e, "loop n, l in FILE_LINES(f) do\n    PRINT(n, l)\nend\n", lng));

        // a directory, and a BOM (dropped — READ_LINES keeps it)
        assertEquals("Runtime error: Runtime Error: " + cannotRead(dir, "it is a directory") + "\n",
                run("x = FILE_LINES(f)\n", dir));
        Path bom = file("bom.txt", "﻿a\nb");
        assertEquals("1 a 2 false\n", run("x = FILE_LINES(f)\nPRINT(LEN(x.1), x.1, LEN(x), x == UNWRAP(READ_LINES(f)))\n", bom));
    }

    @Test void aFileAboveTheWholeUseLimitCanOnlyBeLooped() throws IOException {
        Path f = file("big.txt", "x".repeat(50) + "\n" + "y".repeat(50) + "\n");   // 102 bytes
        Engine e = new Engine().setFileLinesLimits(100, 0);
        assertEquals("50\n50\n2\n", run(e, "loop l in FILE_LINES(f) do\n    PRINT(LEN(l))\nend\nPRINT(LEN(FILE_LINES(f)))\n", f));
        String tooLarge = "Runtime error: Runtime Error: FILE_LINES() '" + f
                + "' is too large to use as a whole (102 bytes, the limit is 100 bytes) — go through it with a loop: loop line in ... do\n";
        assertEquals(tooLarge, run(e, "x = FILE_LINES(f)\nPRINT(x.1)\n", f));
        assertEquals(tooLarge, run(e, "PRINT(FILE_LINES(f))\n", f));
        assertEquals(tooLarge, run(e, "return FILE_LINES(f)\n", f));           // the host boundary, inside the run
        assertEquals("", run(e, "x = FILE_LINES(f)\ny = x\n", f));              // holding and copying it is fine
    }

    @Test void hostBoundariesReceivePlainArrays() throws IOException {
        Path f = file("h.txt", "a\nb\n");
        Engine e = new Engine();
        e.registerExternal("KIND", a -> a.get(0).getClass().getSimpleName());
        assertEquals("ArrayList\n", run(e, "PRINT(KIND(FILE_LINES(f)).value)\n", f));

        Map<String, Object> props = Map.of("f", f.toString());
        Engine.Outcome returned = e.execute("return FILE_LINES(f)\n", props, l -> {}, l -> {});
        assertEquals(ArrayList.class, returned.returnValue().getClass());
        assertEquals(List.of("a", "b"), returned.returnValue());

        Engine.Outcome global = e.execute("result = FILE_LINES(f)\n", props, l -> {}, l -> {});
        assertTrue(global.globals().get("result") instanceof LazyLines);     // unread inside the run
        assertEquals(ArrayList.class, global.result().getClass());           // plain for the host
        assertEquals(List.of("a", "b"), global.result());
        assertEquals(ArrayList.class, TypeChecker.deepCopy(global.globals().get("result")).getClass());

        assertTrue(Builtins.standard((PlatformProvider) null).names().contains("FILE_LINES"));
    }

    @Test void oneValueSharedByManyThreads() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 3000; i++) sb.append("row ").append(i).append('\n');
        Path f = file("rows.txt", sb.toString());
        String script = """
                shared = FILE_LINES(f)
                function probe(k) do
                    n = 0
                    loop line in ::shared infinite do
                        n = n + 1
                    end
                    return n + LEN(::shared) + LEN(SLICE(::shared, 2999, 2))
                end
                multi r do
                    loop i in [1..20] infinite do
                        thread : probe(i)
                    end
                monitor 5
                    x = LEN(::shared)
                end
                ok = 0
                loop k, v in r infinite do
                    if v.ok and v.value == 6002 then
                        ok = ok + 1
                    end
                end
                PRINT(ok)
                """;
        assertEquals("20\n", run(script, f));
    }

    @Test void theLoopCapAppliesAsToAnyArray() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 1001; i++) sb.append(i).append('\n');
        Path f = file("many.txt", sb.toString());
        assertEquals("Runtime error: Runtime Error at line 1:0: Loop exceeded maximum iterations (1000)."
                + " Use 'loop ... infinite do' if you need unlimited iterations.\n",
                run("loop l in FILE_LINES(f) do\n    x = l\nend\n", f));
        assertEquals("1001\n", run("n = 0\nloop l in FILE_LINES(f) infinite do\n    n = n + 1\nend\nPRINT(n)\n", f));
    }
}
