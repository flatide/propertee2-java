package com.flatide.propertee2.cli;

import com.flatide.propertee2.Parsing;
import com.flatide.propertee2.interp.DebugFrame;
import com.flatide.propertee2.interp.DebugQuit;
import com.flatide.propertee2.interp.Engine;
import com.flatide.propertee2.value.TeeError;
import com.flatide.propertee2.value.TeeFormat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * The interactive REPL behind bare {@code propertee2} (no script file) and {@code propertee2 -i
 * script.tee}. One {@link Engine.Session} holds the state — globals and user functions persist
 * across inputs; each input runs with full script semantics ({@code SLEEP}, {@code multi}/
 * {@code thread}, builtins).
 *
 * <p>Input handling: a line that parses runs immediately. A line whose parse fails <i>at end of
 * input</i> (an unclosed {@code if}/{@code loop}/{@code function} block, a dangling operator) opens
 * a continuation — lines accumulate until the buffer parses; a blank line abandons the attempt and
 * reports the syntax error. The value of a trailing bare expression (or a top-level {@code return})
 * is echoed in {@code PRINT} display form; the empty object {@code {}} — the language's "absence" —
 * is not echoed. Script runtime errors print the same {@code Runtime error:} line as script mode
 * and the session continues. Prompts and the banner appear only when stdin is a terminal, so piped
 * input produces clean output.
 *
 * <p>Debugging (0.22.0): in the REPL — including {@code :load} and {@code -i} — the {@code debug}
 * statement is a breakpoint. Execution freezes and a {@code debug>} prompt opens <i>in the paused
 * scope</i>: bare input evaluates there (reading and writing the paused function's locals),
 * {@code :c} resumes, {@code :q} aborts the run keeping the session, {@code :vars}/{@code :globals}
 * dump state. Plain script mode ({@code propertee2 script.tee}) keeps {@code debug} a no-op, as the
 * spec's "normal execution" requires.
 */
final class Repl {
    private Repl() {}

    static void run(Map<String, Object> props, BufferedReader in, PrintStream out, PrintStream err,
                    boolean interactive, String version) throws IOException {
        run(props, in, out, err, interactive, version, null);
    }

    static void run(Map<String, Object> props, BufferedReader in, PrintStream out, PrintStream err,
                    boolean interactive, String version, String preload) throws IOException {
        // SHELL works in the REPL too (0.19.0): the same in-process SimpleTaskRunner as script mode.
        com.flatide.propertee2.task.SimpleTaskRunner taskRunner = new com.flatide.propertee2.task.SimpleTaskRunner();
        try {
            run(props, taskRunner, in, out, err, interactive, version, preload);
        } finally {
            taskRunner.shutdown();
        }
    }

    private static void run(Map<String, Object> props, com.flatide.propertee2.task.SimpleTaskRunner taskRunner,
                            BufferedReader in, PrintStream out, PrintStream err,
                            boolean interactive, String version, String preload) throws IOException {
        Engine.Session session = new Engine().setTaskRunner(taskRunner).session(props, out::println, err::println)
                .setDebugHandler(frame -> debugSession(frame, in, out, err, interactive));
        if (interactive) {
            out.println("propertee2 " + version + " REPL — :help for commands, :exit or Ctrl-D to quit");
        }
        if (preload != null) {
            load(session, preload, out, err);
        }

        StringBuilder buffer = new StringBuilder();
        while (true) {
            if (interactive) {
                out.print(buffer.isEmpty() ? "pt> " : "...> ");
                out.flush();
            }
            String line = in.readLine();
            if (line == null) {
                if (interactive) out.println();
                return;                                        // EOF (Ctrl-D / end of piped input)
            }

            if (buffer.isEmpty()) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                if (trimmed.equals(":exit") || trimmed.equals(":quit")) return;
                if (trimmed.equals(":help")) {
                    out.println("""
                            ProperTee REPL. Statements run as typed; globals and functions persist.
                            An unclosed block (if/loop/function ... end) continues on the next line;
                            a blank line abandons an unfinished input. The value of a trailing bare
                            expression is echoed ({} is not). A debug statement opens the debugger.
                              :help          this help
                              :load <file>   run a script in this session (state accumulates)
                              :exit, :quit   leave the REPL (Ctrl-D also works)""");
                    continue;
                }
                if (trimmed.startsWith(":load")) {
                    String path = trimmed.substring(5).trim();
                    if (path.isEmpty()) {
                        err.println("propertee2: usage: :load <file>");
                    } else {
                        load(session, path, out, err);
                    }
                    continue;
                }
            }

            buffer.append(line).append('\n');
            String snippet = buffer.toString();
            try {
                Parsing.parse(snippet);
            } catch (Parsing.SyntaxException e) {
                if (e.atEof() && !line.trim().isEmpty()) {
                    continue;                                  // incomplete construct: keep reading
                }
                err.println("propertee2: " + e.getMessage());
                buffer.setLength(0);
                continue;
            }
            buffer.setLength(0);

            evalAndReport(session, snippet, out);
        }
    }

    /** {@code :load} / {@code -i} preload: run a whole file in the session, debugger active. */
    private static void load(Engine.Session session, String path, PrintStream out, PrintStream err) {
        String source;
        try {
            source = Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            err.println("propertee2: cannot read " + path + ": " + e.getMessage());
            return;
        }
        try {
            Parsing.parse(source);
        } catch (Parsing.SyntaxException e) {
            err.println("propertee2: " + path + ": " + e.getMessage());
            return;
        }
        evalAndReport(session, source, out);
    }

    /** Shared eval tail: echo, script-error, and debugger-abort reporting. */
    private static void evalAndReport(Engine.Session session, String snippet, PrintStream out) {
        try {
            Object echo = session.eval(snippet);
            if (echo != null && !isEmptyObject(echo)) {
                out.println(TeeFormat.display(echo));
            }
        } catch (TeeError e) {
            // same contract as script mode: a script runtime error is normal output
            out.println("Runtime error: " + e.positioned());
        } catch (DebugQuit e) {
            out.println("debug: run aborted; session state kept");
        }
    }

    /**
     * The pdb-style prompt at a {@code debug} statement. Runs on the paused fiber — the whole run
     * is frozen while it reads. Bare input evaluates in the paused scope (same continuation rules
     * as the main loop); EOF continues the run, so piped input that ends mid-debug just resumes.
     */
    private static void debugSession(DebugFrame frame, BufferedReader in, PrintStream out, PrintStream err,
                                     boolean interactive) {
        out.println("debug at line " + frame.line() + ":" + frame.column()
                + (interactive ? " — :c continues, :help for commands" : ""));
        StringBuilder buffer = new StringBuilder();
        while (true) {
            if (interactive) {
                out.print(buffer.isEmpty() ? "debug> " : "...> ");
                out.flush();
            }
            String line;
            try {
                line = in.readLine();
            } catch (IOException e) {
                return;                                        // input gone: resume the run
            }
            if (line == null) return;                          // EOF: continue the run

            if (buffer.isEmpty()) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                switch (trimmed) {
                    case ":c", ":continue" -> { return; }
                    case ":q", ":quit" -> { frame.quit(); return; }
                    case ":vars" -> {
                        dump(out, frame.locals(), "(no locals — top level; use :globals)");
                        continue;
                    }
                    case ":globals" -> {
                        dump(out, frame.globals(), "(no globals)");
                        continue;
                    }
                    case ":help" -> {
                        out.println("""
                                ProperTee debugger — the run is paused at the debug statement.
                                Bare input evaluates in the paused scope: locals and globals are
                                readable AND assignable; the trailing expression value is echoed.
                                  :c, :continue  resume the run
                                  :q, :quit      abort the run (the REPL session keeps its state)
                                  :vars          the paused function's parameters and locals
                                  :globals       the global variables
                                  :help          this help""");
                        continue;
                    }
                    default -> { }
                }
            }

            buffer.append(line).append('\n');
            String snippet = buffer.toString();
            try {
                Parsing.parse(snippet);
            } catch (Parsing.SyntaxException e) {
                if (e.atEof() && !line.trim().isEmpty()) {
                    continue;                                  // incomplete construct: keep reading
                }
                err.println("propertee2: " + e.getMessage());
                buffer.setLength(0);
                continue;
            }
            buffer.setLength(0);

            try {
                Object echo = frame.eval(snippet);
                if (echo != null && !isEmptyObject(echo)) {
                    out.println(TeeFormat.display(echo));
                }
            } catch (TeeError e) {
                out.println("Runtime error: " + e.positioned());
            }
        }
    }

    private static void dump(PrintStream out, Map<String, Object> vars, String emptyNote) {
        if (vars.isEmpty()) {
            out.println(emptyNote);
            return;
        }
        for (Map.Entry<String, Object> e : vars.entrySet()) {
            out.println(e.getKey() + " = " + TeeFormat.display(e.getValue()));
        }
    }

    private static boolean isEmptyObject(Object v) {
        return v instanceof Map<?, ?> m && m.isEmpty();
    }
}
