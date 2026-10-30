package com.flatide.propertee2.interp;

import com.flatide.propertee2.module.ModuleRequest;
import com.flatide.propertee2.module.ModuleResolver;
import com.flatide.propertee2.module.ModuleSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportTest {

    private static ModuleResolver modules(Map<String, String> sources) {
        return request -> {
            String key = request.displayName();
            String source = sources.get(key);
            if (source == null) return null;
            return new ModuleSource(request.moduleId(), request.version(), "module:" + key, source);
        };
    }

    @Test void importSkipsTopLevelAndExposesOnlyFunctions() {
        String out = new Engine().setModuleResolver(modules(Map.of("util.file", """
                PRINT("module top level")
                function greet(name) do
                    return "hello " + name
                end
                """))).run("""
                import util.file as file
                PRINT(file::greet("Ada"))
                """);

        assertEquals("hello Ada\n", out);
    }

    @Test void moduleGlobalsAreIsolatedAndInitIsExplicit() {
        String out = new Engine().setModuleResolver(modules(Map.of("counter", """
                function init(n) do
                    ::n = n
                end
                function bump() do
                    ::n = ::n + 1
                    return helper()
                end
                function helper() do
                    return ::n
                end
                """))).run("""
                import counter as c
                n = 100
                c::init(4)
                PRINT(c::bump())
                PRINT(n)
                """);

        assertEquals("5\n100\n", out);
    }

    @Test void argumentsAndReturnsAreCopiedAcrossModuleBoundary() {
        String out = new Engine().setModuleResolver(modules(Map.of("mut", """
                function touch(a) do
                    a.1 = 9
                    return a
                end
                """))).run("""
                import mut as m
                a = [1, 2]
                b = m::touch(a)
                b.2 = 8
                PRINT(a)
                PRINT(b)
                """);

        assertEquals("[ 1, 2 ]\n[ 9, 8 ]\n", out);
    }

    @Test void exactVersionIsPassedToResolver() {
        AtomicReference<ModuleRequest> seen = new AtomicReference<>();
        String out = new Engine().setModuleResolver(request -> {
            seen.set(request);
            return new ModuleSource(request.moduleId(), request.version(), "tool.2", """
                    function version() do return 2 end
                    """);
        }).run("import tool.file.2 as tool\nPRINT(tool::version())");

        assertEquals(new ModuleRequest("tool.file", 2), seen.get());
        assertEquals("2\n", out);
    }

    @Test void nestedImportFailsBeforeEntrySideEffects() {
        String out = new Engine().setModuleResolver(modules(Map.of("outer", """
                import inner as i
                function f() do return 1 end
                """))).run("""
                import outer as o
                PRINT("must not run")
                """);

        assertTrue(out.startsWith("Runtime error: Runtime Error at module:outer line 1:1:"), out);
        assertTrue(!out.contains("must not run"), out);
    }

    @Test void entryImportErrorsKeepThePathFreeCompatibilityFormat() {
        String out = new Engine().setSourceId("/abs/entry.tee").run("import missing as m\n");
        assertEquals("Runtime error: Runtime Error at line 1:1: import requires a module resolver\n", out);

        String duplicate = new Engine().setSourceId("/abs/entry.tee")
                .setModuleResolver(modules(Map.of(
                        "one", "function value() do return 1 end\n",
                        "two", "function value() do return 2 end\n")))
                .run("import one as m\nimport two as m\n");
        assertEquals("Runtime error: Runtime Error at line 2:1: Duplicate import alias 'm'\n", duplicate);

        String resolution = new Engine().setSourceId("/abs/entry.tee")
                .setModuleResolver(request -> {
                    throw new com.flatide.propertee2.module.ModuleResolutionException(
                            "Module path escapes module root: " + request.displayName());
                })
                .run("import escape as m\n");
        assertEquals("Runtime error: Runtime Error at line 1:1: Module path escapes module root: escape\n",
                resolution);
    }

    @Test void workerReadsModuleSnapshotAndCannotWriteIt() {
        String out = new Engine().setModuleResolver(modules(Map.of("state", """
                function init() do ::x = 1 end
                function read() do return ::x end
                function write() do ::x = 2 return ::x end
                """))).run("""
                import state as s
                s::init()
                multi r do
                    thread a: s::read()
                    thread b: s::write()
                end
                PRINT(r.a.value)
                PRINT(r.b.status)
                """);

        assertTrue(out.contains("1\n"), out);
        assertTrue(out.endsWith("error\n"), out);
        assertTrue(out.contains("module:state line 3:"), out);
    }

    @Test void debuggerIdentifiesImportedSourceAndStillExcludesMonitor() {
        List<String> breaks = new ArrayList<>();
        Engine.Session session = new Engine()
                .setSourceId("entry.tee")
                .setModuleResolver(modules(Map.of("worker", """
                        function run() do
                            debug
                            return 7
                        end
                        """)))
                .session(Map.of(), ignored -> {}, ignored -> {})
                .setDebugHandler(frame -> breaks.add(frame.sourceId() + ":" + frame.line()
                        + ":" + frame.threadId()));

        session.eval("""
                import worker as w
                multi r do
                    thread x: w::run()
                monitor 1
                    debug
                end
                """);

        assertEquals(List.of("module:worker:2:1"), breaks);
    }

    @Test void rejectedSessionImportDoesNotPartiallyInstallEarlierAliases() {
        List<String> output = new ArrayList<>();
        Engine.Session session = new Engine().setModuleResolver(modules(Map.of(
                        "first", "function value() do return 1 end\n",
                        "second", "function value() do return 2 end\n",
                        "third", "function value() do return 3 end\n")))
                .session(Map.of(), output::add, ignored -> {});

        session.eval("import first as one\n");
        assertThrows(com.flatide.propertee2.value.TeeError.class,
                () -> session.eval("import second as two\nimport third as one\n"));
        session.eval("import second as two\nPRINT(two::value())\n");

        assertEquals(List.of("2"), output);
        assertThrows(com.flatide.propertee2.value.TeeError.class,
                () -> session.eval("import first as another\n"));
    }

    @Test void reImportingTheSameAliasRebindsToCurrentContentWithFreshGlobals() {
        List<String> output = new ArrayList<>();
        Map<String, String> sources = new java.util.HashMap<>();
        sources.put("util", """
                function init(n) do
                    ::n = n
                end
                function get() do
                    return ::n
                end
                """);
        Engine.Session session = new Engine()
                .setModuleResolver(request ->
                        sources.containsKey(request.displayName())
                                ? new ModuleSource(request.moduleId(), request.version(),
                                        "module:" + request.displayName(), sources.get(request.displayName()))
                                : null)
                .session(Map.of(), output::add, ignored -> {});

        // First run: initialize the module global, read it back.
        session.eval("import util as u\nu::init(10)\nPRINT(u::get())\n");
        // Edit the module's stored content, then re-import the same alias: the re-run must see the
        // NEW content (a renamed function) and start from EMPTY module globals.
        sources.put("util", """
                function tag() do
                    return "v2"
                end
                function get() do
                    ::calls = 1
                    return ::calls
                end
                """);
        session.eval("import util as u\nPRINT(u::tag())\nPRINT(u::get())\n");

        assertEquals(List.of("10", "v2", "1"), output);   // v2 content runs; globals start fresh
    }
}
