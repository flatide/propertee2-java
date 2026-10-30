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
 * <p>Debugging (0.22.0): in the REPL — including {@code :run}, {@code :debug} and {@code -i} — the
 * {@code debug} statement is a breakpoint. Execution freezes and a {@code debug>} prompt opens <i>in the paused
 * scope</i>: bare input evaluates there (reading and writing the paused function's locals),
 * {@code :c} resumes, {@code :q} aborts the run keeping the session, {@code :vars}/{@code :globals}
 * dump state. Plain script mode ({@code propertee2 script.tee}) keeps {@code debug} a no-op, as the
 * spec's "normal execution" requires.
 *
 * <p>Re-running (0.31.0): {@code :run [file]} re-executes a script in the same session (defaulting
 * to the last one loaded), and {@code :debug [file]} does the same but stops before the first
 * statement — a restart under the debugger without editing a {@code debug} statement in. Either
 * one re-resolves the script's imports against their current stored content with fresh module
 * globals, so a re-run reflects edits to imported modules. {@code :list [file]} prints a script
 * with 1-based line numbers (marking breakpoint lines), matching the numbers {@code :b} uses.
 *
 * <p>Loading (0.34.0): {@code :load <file>} only reads and syntax-checks a script and makes it the
 * default target of {@code :run}/{@code :debug}/{@code :list} — it does not run it, so the script
 * can be listed and given breakpoints before its first run. {@code -i script.tee} still runs the
 * script before the first prompt, like {@code python -i}.
 */
final class Repl {
    private Repl() {}

    static void run(Map<String, Object> props, BufferedReader in, PrintStream out, PrintStream err,
                    boolean interactive, String version) throws IOException {
        run(props, java.util.List.of(), in, out, err, interactive, version, null);
    }

    static void run(Map<String, Object> props, BufferedReader in, PrintStream out, PrintStream err,
                    boolean interactive, String version, String preload) throws IOException {
        run(props, java.util.List.of(), in, out, err, interactive, version, preload);
    }

    static void run(Map<String, Object> props, java.util.List<String> args,
                    BufferedReader in, PrintStream out, PrintStream err,
                    boolean interactive, String version, String preload) throws IOException {
        run(props, args, in, out, err, interactive, version, preload, Path.of("."));
    }

    static void run(Map<String, Object> props, java.util.List<String> args,
                    BufferedReader in, PrintStream out, PrintStream err,
                    boolean interactive, String version, String preload,
                    Path moduleRoot) throws IOException {
        // SHELL works in the REPL too (0.19.0): the same in-process SimpleTaskRunner as script mode.
        com.flatide.propertee2.task.SimpleTaskRunner taskRunner = new com.flatide.propertee2.task.SimpleTaskRunner();
        try {
            run(props, args, taskRunner, in, out, err, interactive, version, preload, moduleRoot);
        } finally {
            taskRunner.shutdown();
        }
    }

    private static void run(Map<String, Object> props, java.util.List<String> args,
                            com.flatide.propertee2.task.SimpleTaskRunner taskRunner,
                            BufferedReader in, PrintStream out, PrintStream err,
                            boolean interactive, String version, String preload,
                            Path moduleRoot) throws IOException {
        String sourceId = preload == null ? "<memory>" : Path.of(preload).toAbsolutePath().normalize().toString();
        Engine.Session session = new Engine().setTaskRunner(taskRunner).setArgs(args)
                .setSourceId(sourceId)
                .setModuleResolver(new FileModuleResolver(moduleRoot))
                // a failure is reported where script mode reports it, before an `on error` handler runs
                .setFailureReporter(e -> out.println("Runtime error: " + e.positioned()))
                .session(props, out::println, err::println);
        session.setDebugHandler(frame ->
                debugSession(frame, session.debugBreakpoints(), in, out, err, interactive));
        if (interactive) {
            out.println("propertee2 " + version + " REPL — :help for commands, :exit or Ctrl-D to quit");
        }
        if (preload != null) {
            runFile(session, preload, out, err, false);
        }
        String lastLoaded = preload;                           // :run/:debug/:list default target

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
                              :load <file>   load a script for :run/:debug/:list without running it
                              :run [file]    run a script in this session (defaults to the loaded one)
                              :debug [file]  run it paused at its first statement (same default)
                              :list [file]   show a script with line numbers (same default)
                              :b [line]      list breakpoints / add one (breaks in the debugger)
                              :bd [line]     delete one breakpoint / all of them
                              :exit, :quit   leave the REPL (Ctrl-D also works)""");
                    continue;
                }
                if (trimmed.equals(":load") || trimmed.startsWith(":load ")) {
                    String path = trimmed.substring(5).trim();
                    if (path.isEmpty()) {
                        err.println("propertee2: usage: :load <file>");
                    } else {
                        // Load only (0.34.0): the script runs on :run / :debug. The target sticks
                        // even when the check fails, so a fix in the editor needs only :run.
                        lastLoaded = path;
                        if (readAndParse(path, err) != null) {
                            out.println("loaded " + path + " — :run runs it, :debug pauses at its first statement");
                        }
                    }
                    continue;
                }
                if (trimmed.equals(":run") || trimmed.startsWith(":run ")
                        || trimmed.equals(":debug") || trimmed.startsWith(":debug ")) {
                    boolean pauseAtEntry = trimmed.startsWith(":debug");
                    String arg = trimmed.substring(pauseAtEntry ? 6 : 4).trim();
                    String path = arg.isEmpty() ? lastLoaded : arg;
                    if (path == null) {
                        err.println("propertee2: no script loaded yet — usage: "
                                + (pauseAtEntry ? ":debug" : ":run") + " [file]");
                    } else {
                        lastLoaded = path;
                        runFile(session, path, out, err, pauseAtEntry);
                    }
                    continue;
                }
                if (trimmed.equals(":list") || trimmed.startsWith(":list ")) {
                    String arg = trimmed.substring(5).trim();     // peek only — does not change :run target
                    String path = arg.isEmpty() ? lastLoaded : arg;
                    if (path == null) {
                        err.println("propertee2: no script loaded yet — usage: :list [file]");
                    } else {
                        listScript(path, session.debugBreakpoints(), out, err);
                    }
                    continue;
                }
                if (breakpointCommand(trimmed, session.debugBreakpoints(), out, err)) continue;
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

    /**
     * {@code :list [file]} — print a script with 1-based line numbers, matching the numbering
     * {@code :b <line>} and the debugger use. A {@code *} marks a line that currently has a
     * breakpoint. This only reads the file (does not run it or change the {@code :run} target).
     */
    private static void listScript(String path, java.util.Set<Integer> breakpoints,
                                   PrintStream out, PrintStream err) {
        String source;
        try {
            source = Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            err.println("propertee2: cannot read " + path + ": " + e.getMessage());
            return;
        }
        // A trailing newline should not render as a spurious final empty line.
        String body = source.endsWith("\n") ? source.substring(0, source.length() - 1) : source;
        String[] lines = body.isEmpty() ? new String[0] : body.split("\n", -1);
        int width = Math.max(2, Integer.toString(lines.length).length());
        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            String mark = breakpoints.contains(lineNo) ? "*" : " ";
            out.println(String.format("%" + width + "d%s %s", lineNo, mark, lines[i]));
        }
    }

    /**
     * {@code -i} preload / {@code :run} / {@code :debug}: run a whole file in the session, debugger
     * active. With {@code pauseAtEntry} ({@code :debug}) the run stops before its first statement,
     * so a loaded script can be run under the debugger without editing a {@code debug} statement
     * into it.
     */
    private static void runFile(Engine.Session session, String path, PrintStream out, PrintStream err,
                                boolean pauseAtEntry) {
        String source = readAndParse(path, err);
        if (source != null) {
            evalAndReport(session, source, out, pauseAtEntry);
        }
    }

    /**
     * Read a script and check its syntax — all {@code :load} does, and the first step of every run.
     * Reports the failure on {@code err} and returns null when the file is unreadable or invalid.
     */
    private static String readAndParse(String path, PrintStream err) {
        String source;
        try {
            source = Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (IOException e) {
            err.println("propertee2: cannot read " + path + ": " + e.getMessage());
            return null;
        }
        try {
            Parsing.parse(source);
        } catch (Parsing.SyntaxException e) {
            err.println("propertee2: " + path + ": " + e.getMessage());
            return null;
        }
        return source;
    }

    /** Shared eval tail: echo, script-error, and debugger-abort reporting. */
    private static void evalAndReport(Engine.Session session, String snippet, PrintStream out) {
        evalAndReport(session, snippet, out, false);
    }

    /** Shared eval tail with an optional entry pause ({@code :debug}). */
    private static void evalAndReport(Engine.Session session, String snippet, PrintStream out,
                                      boolean pauseAtEntry) {
        try {
            Object echo = session.eval(snippet, pauseAtEntry);
            if (echo != null && !isEmptyObject(echo)) {
                out.println(TeeFormat.display(echo));
            }
        } catch (TeeError e) {
            // same contract as script mode: a script runtime error is normal output — reported
            // already when the snippet's `on error` handler ran (spec v0.37.0)
            if (e.handler() == null) out.println("Runtime error: " + e.positioned());
        } catch (DebugQuit e) {
            out.println("debug: run aborted; session state kept");
        }
    }

    /**
     * The pdb-style prompt at a {@code debug} statement. Runs on the paused fiber — the whole run
     * is frozen while it reads. Bare input evaluates in the paused scope (same continuation rules
     * as the main loop); EOF continues the run, so piped input that ends mid-debug just resumes.
     */
    private static void debugSession(DebugFrame frame, java.util.Set<Integer> breakpoints,
                                     BufferedReader in, PrintStream out, PrintStream err,
                                     boolean interactive) {
        // the column from 1, as in the error report (spec v0.37.0); the frame counts it from 0
        String position = "line " + frame.line() + ":" + (frame.column() + 1);
        if (frame.threadId() != 0) {
            position += " in worker " + frame.threadId() + " (" + frame.threadName() + ")";
        }
        for (var ret : frame.returns()) {         // 0.29.0: auto-display crossed return values
            out.println(ret.function() + " returned " + TeeFormat.display(ret.value()));
        }
        switch (frame.reason()) {
            case DEBUG_STATEMENT -> out.println("debug at " + position
                    + (interactive ? " — :c continues, :help for commands" : ""));
            case BREAKPOINT -> out.println("breakpoint at " + position + ": " + frame.statementText());
            case STEP -> out.println("at " + position + ": " + frame.statementText());
        }
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
                    case ":n", ":next" -> { frame.stepOver(); return; }
                    case ":s", ":step" -> { frame.stepInto(); return; }
                    case ":r", ":return" -> { frame.stepOut(); return; }
                    case ":w", ":where" -> {
                        StringBuilder chain = new StringBuilder("top level");
                        for (var call : frame.callStack()) {
                            chain.append(" → ").append(call.function())
                                 .append(" (called at ");
                            if (call.sourceId() != null && !call.sourceId().startsWith("<")) {
                                chain.append(call.sourceId()).append(' ');
                            }
                            chain.append(call.line()).append(':').append(call.column() + 1).append(')');
                        }
                        out.println(chain);
                        out.println("at " + position + ": " + frame.statementText());
                        continue;
                    }
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
                                ProperTee debugger — the run is paused before the shown statement.
                                Bare input evaluates in the paused scope: locals and globals are
                                readable AND assignable; the trailing expression value is echoed.
                                  :c, :continue  resume the run
                                  :n, :next      step to the next statement (over calls)
                                  :s, :step      step into function calls
                                  :r, :return    run until the current function returns
                                  :w, :where     call stack and current position
                                  :b [line]      list breakpoints / add one
                                  :bd [line]     delete one breakpoint / all of them
                                  :vars          the paused function's parameters and locals
                                  :globals       the global variables
                                  :q, :quit      abort the run (the REPL session keeps its state)
                                  :help          this help""");
                        continue;
                    }
                    default -> {
                        if (breakpointCommand(trimmed, breakpoints, out, err)) continue;
                    }
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

    /**
     * {@code :b [line]} / {@code :bd [line]} — shared by the {@code pt>} prompt (set breakpoints
     * before a {@code :run}/{@code :debug}) and the {@code debug>} prompt. Returns true when it
     * consumed the input.
     */
    private static boolean breakpointCommand(String trimmed, java.util.Set<Integer> breakpoints,
                                             PrintStream out, PrintStream err) {
        if (!trimmed.equals(":b") && !trimmed.equals(":bd")
                && !trimmed.startsWith(":b ") && !trimmed.startsWith(":bd ")) {
            return false;
        }
        boolean delete = trimmed.startsWith(":bd");
        String arg = trimmed.substring(delete ? 3 : 2).trim();
        if (arg.isEmpty()) {
            if (delete) {
                breakpoints.clear();
                out.println("all breakpoints deleted");
            } else if (breakpoints.isEmpty()) {
                out.println("(no breakpoints)");
            } else {
                out.println("breakpoints: " + breakpoints.stream().sorted()
                        .map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse(""));
            }
            return true;
        }
        int lineNo;
        try {
            lineNo = Integer.parseInt(arg);
        } catch (NumberFormatException e) {
            err.println("propertee2: not a line number: " + arg);
            return true;
        }
        if (delete) {
            out.println(breakpoints.remove(lineNo)
                    ? "breakpoint deleted at line " + lineNo : "no breakpoint at line " + lineNo);
        } else {
            breakpoints.add(lineNo);
            out.println("breakpoint added at line " + lineNo);
        }
        return true;
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
