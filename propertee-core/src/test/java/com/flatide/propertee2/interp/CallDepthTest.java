package com.flatide.propertee2.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.flatide.propertee2.module.ModuleResolver;
import com.flatide.propertee2.module.ModuleSource;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Script function calls nest 64 deep and no deeper. The spec leaves the depth to the runtime, and
 * this one had no limit: a function calling itself without end ran until the Java stack
 * overflowed, or — where each call hands the baton over before the next — until the heap was full.
 */
class CallDepthTest {

    private static final String LIMIT =
            "Function calls exceeded maximum depth (64). Check for a function that calls itself without end.";

    private static final String DOWN = """
            function down(n, max) do
                if n >= max then return n end
                return down(n + 1, max)
            end
            """;

    @Test void callsNestSixtyFourDeep() {
        assertEquals("64\n", new Engine().run(DOWN + "PRINT(down(1, 64))\n"));
    }

    @Test void theSixtyFifthIsARuntimeErrorAtItsCall() {
        assertEquals("Runtime error: Runtime Error at line 3:12: " + LIMIT + "\n",
                new Engine().run(DOWN + "PRINT(down(1, 65))\n"));
    }

    /** The depth is counted, not used up: calls that returned no longer count. */
    @Test void callsOneAfterAnotherDoNotAddUp() {
        assertEquals("200\n", new Engine().run(DOWN + """
                total = 0
                loop i in [1, 2, 3, 4] do
                    total = total + down(1, 50)
                end
                PRINT(total)
                """));
    }

    @Test void theHandlerRunsAfterTheLimitAndCanCallFunctions() {
        assertEquals("Runtime error: Runtime Error at line 6:12: " + LIMIT + "\nhandler: 64\n",
                new Engine().run("""
                on error do
                    PRINT("handler:", down(1, 64))
                end
                function down(n, max) do
                    if n >= max then return n end
                    return down(n + 1, max)
                end
                down(1, 1000)
                """));
    }

    /**
     * With a statement ahead of the call and another worker ready, every call hands the baton
     * over and the Java stack is set aside on the heap, where it does not overflow: beside a busy
     * worker it grew until the heap was full. It is that thread's error at 64, and the worker
     * beside it finishes.
     */
    @Test void aWorkerThatCallsItselfWithoutEndIsThatThreadsError() {
        String out = assertTimeoutPreemptively(Duration.ofSeconds(30), () -> new Engine().run("""
                function down(n) do
                    next = n + 1
                    return down(next)
                end
                function count(n) do
                    i = 0
                    loop i < n infinite do
                        i = i + 1
                    end
                    return i
                end
                multi r do
                    thread a: down(1)
                    thread b: count(5000)
                end
                PRINT(r.a.status, r.a.value)
                PRINT(r.b.status, r.b.value)
                """));
        String error = "Runtime Error at line 3:12: " + LIMIT;
        assertEquals("[THREAD ERROR] " + error + "\nerror " + error + "\ndone 5000\n", out);
    }

    /**
     * A worker starts at the depth of the block that started it, so a function cannot go deeper by
     * starting itself as a thread — which, each worker having a stack of its own, did not end.
     */
    @Test void aWorkerGoesOnFromTheDepthOfItsMultiBlock() {
        String out = assertTimeoutPreemptively(Duration.ofSeconds(30), () -> new Engine().run("""
                function chain(n) do
                    multi r do
                        thread next: chain(n + 1)
                    end
                    if r.next.ok then return r.next.value end
                    return n
                end
                PRINT(chain(1))
                """));
        assertEquals("[THREAD ERROR] Runtime Error at line 3:22: " + LIMIT + "\n64\n", out);
    }

    /** A call into a module goes on from the caller's depth. */
    @Test void aModuleCallGoesOnFromTheCallersDepth() {
        ModuleResolver modules = request -> new ModuleSource(request.moduleId(), request.version(),
                "module:" + request.displayName(), DOWN);
        assertEquals("64\n", new Engine().setModuleResolver(modules).run("""
                import util.deep as deep
                PRINT(deep::down(1, 64))
                """));
        assertEquals("Runtime error: Runtime Error at module:util.deep line 3:12: " + LIMIT + "\n",
                new Engine().setModuleResolver(modules).run("""
                import util.deep as deep
                function wrap() do
                    return deep::down(1, 64)
                end
                PRINT(wrap())
                """));
    }

    /** The loop limit and this one are separate: a loop may call a function as often as it likes. */
    @Test void aLoopOfCallsIsNotNesting() {
        assertEquals("1000\n", new Engine().run("""
                function one() do return 1 end
                total = 0
                loop i < 1000 do
                    total = total + one()
                    i = total
                end
                PRINT(total)
                """, Map.of("i", 0)));
    }
}
