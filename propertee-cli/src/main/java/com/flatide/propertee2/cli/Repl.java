package com.flatide.propertee2.cli;

import com.flatide.propertee2.Parsing;
import com.flatide.propertee2.interp.Engine;
import com.flatide.propertee2.value.TeeError;
import com.flatide.propertee2.value.TeeFormat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Map;

/**
 * The interactive REPL behind bare {@code propertee2} (no script file). One {@link Engine.Session}
 * holds the state — globals and user functions persist across inputs; each input runs with full
 * script semantics ({@code SLEEP}, {@code multi}/{@code thread}, builtins).
 *
 * <p>Input handling: a line that parses runs immediately. A line whose parse fails <i>at end of
 * input</i> (an unclosed {@code if}/{@code loop}/{@code function} block, a dangling operator) opens
 * a continuation — lines accumulate until the buffer parses; a blank line abandons the attempt and
 * reports the syntax error. The value of a trailing bare expression (or a top-level {@code return})
 * is echoed in {@code PRINT} display form; the empty object {@code {}} — the language's "absence" —
 * is not echoed. Script runtime errors print the same {@code Runtime error:} line as script mode
 * and the session continues. Prompts and the banner appear only when stdin is a terminal, so piped
 * input produces clean output.
 */
final class Repl {
    private Repl() {}

    static void run(Map<String, Object> props, BufferedReader in, PrintStream out, PrintStream err,
                    boolean interactive, String version) throws IOException {
        // SHELL works in the REPL too (0.19.0): the same in-process SimpleTaskRunner as script mode.
        com.flatide.propertee2.task.SimpleTaskRunner taskRunner = new com.flatide.propertee2.task.SimpleTaskRunner();
        try {
            run(props, taskRunner, in, out, err, interactive, version);
        } finally {
            taskRunner.shutdown();
        }
    }

    private static void run(Map<String, Object> props, com.flatide.propertee2.task.SimpleTaskRunner taskRunner,
                            BufferedReader in, PrintStream out, PrintStream err,
                            boolean interactive, String version) throws IOException {
        Engine.Session session = new Engine().setTaskRunner(taskRunner).session(props, out::println, err::println);
        if (interactive) {
            out.println("propertee2 " + version + " REPL — :help for commands, :exit or Ctrl-D to quit");
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
                            expression is echoed ({} is not).
                              :help          this help
                              :exit, :quit   leave the REPL (Ctrl-D also works)""");
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

            try {
                Object echo = session.eval(snippet);
                if (echo != null && !isEmptyObject(echo)) {
                    out.println(TeeFormat.display(echo));
                }
            } catch (TeeError e) {
                // same contract as script mode: a script runtime error is normal output
                out.println("Runtime error: " + e.positioned());
            }
        }
    }

    private static boolean isEmptyObject(Object v) {
        return v instanceof Map<?, ?> m && m.isEmpty();
    }
}
