package com.flatide.propertee2.cli;

import com.flatide.propertee2.Parsing;
import com.flatide.propertee2.interp.Engine;
import com.flatide.propertee2.task.SimpleTaskRunner;
import com.flatide.propertee2.value.JsonParser;
import com.flatide.propertee2.value.TeeError;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Command-line runner: {@code propertee2 [-p <json-object>] [script.tee]}.
 * Reads the script, runs it through the {@link Engine}, and prints its stdout; with no script file
 * it starts the interactive {@link Repl} on stdin. Host-injected built-in properties are supplied
 * as a JSON object via {@code -p} (LANGUAGE.md §Built-in Properties).
 *
 * <p>Exit codes: 0 = ran (a script runtime error is part of stdout, as the conformance suite expects);
 * 2 = bad command-line usage; 1 = the input file could not be read.
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        Map<String, Object> props = Map.of();
        String file = null;
        boolean interactive = false;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-h", "--help" -> { usage(System.out); return; }
                case "-v", "--version" -> { System.out.println("propertee2 " + version()); return; }
                case "-p", "--props" -> {
                    if (i + 1 >= args.length) fail("missing JSON object after " + a);
                    props = parseProps(args[++i]);
                }
                case "-i", "--interactive" -> interactive = true;
                default -> {
                    if (a.startsWith("-")) fail("unknown option: " + a);
                    if (file != null) fail("only one input file is allowed");
                    file = a;
                }
            }
        }
        if (file == null || interactive) {
            // Interactive REPL (0.18.0): bare `propertee2`, or `-i script.tee` (0.22.0) which runs
            // the script in the session first — with the debug statement live as a breakpoint —
            // then drops into the REPL sharing its state, like `python -i`. Prompts only when
            // stdin is a terminal, so `echo 'PRINT(1)' | propertee2` emits clean output.
            try {
                Repl.run(props,
                        new java.io.BufferedReader(new java.io.InputStreamReader(System.in, StandardCharsets.UTF_8)),
                        System.out, System.err, System.console() != null, version(), file);
            } catch (IOException e) {
                System.err.println("propertee2: " + e.getMessage());
                System.exit(1);
            }
            return;
        }
        run(file, props);
    }

    private static void run(String file, Map<String, Object> props) {

        String source;
        try {
            source = Files.readString(Path.of(file), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            System.err.println("propertee2: no such file: " + file);
            System.exit(1);
            return;
        } catch (IOException e) {
            System.err.println("propertee2: cannot read " + file + ": " + e.getMessage());
            System.exit(1);
            return;
        }

        // SHELL works out of the box (0.19.0, like the v1 CLI): an in-process SimpleTaskRunner
        // executes commands via /bin/sh -c. The bare Engine keeps its UnsupportedTaskRunner
        // default — only the CLI wires this.
        SimpleTaskRunner taskRunner = new SimpleTaskRunner();
        try {
            // Live sinks (like the v1 CLI): program output streams to stdout as it prints; runtime
            // diagnostics ([THREAD ERROR]/[MONITOR ERROR]/loop warnings) go to stderr, as in v1/js.
            new Engine().setTaskRunner(taskRunner)
                    .run(source, props, System.out::println, System.err::println);
        } catch (Parsing.SyntaxException e) {
            // malformed input: a concise stderr line + nonzero exit (a script *runtime* error is in stdout)
            System.err.println("propertee2: " + file + ": " + e.getMessage());
            System.exit(1);
        } catch (TeeError e) {
            // script runtime error: part of normal output (stdout, exit 0) — the conformance contract
            System.out.println("Runtime error: " + e.positioned());
        } finally {
            taskRunner.shutdown();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseProps(String json) {
        Object parsed;
        try {
            parsed = JsonParser.parse(json);
        } catch (TeeError e) {
            fail("-p value is not valid JSON: " + e.getMessage());
            return Map.of();   // unreachable
        }
        if (!(parsed instanceof Map)) fail("-p value must be a JSON object");
        return (Map<String, Object>) parsed;
    }

    private static void fail(String message) {
        System.err.println("propertee2: " + message);
        usage(System.err);
        System.exit(2);
    }

    private static void usage(PrintStream out) {
        out.println("""
                usage: propertee2 [-p <json-object>] [-i] [script.tee]

                  With no script file, starts an interactive REPL on stdin. In the REPL
                  (and under -i) a `debug` statement pauses the run and opens a pdb-style
                  debug> prompt in the paused scope.

                  -p, --props <json>   host-injected built-in properties (a JSON object)
                  -i, --interactive    run the script in a REPL session, then stay in the
                                       REPL with its state (debug statements break)
                  -v, --version        print version and exit
                  -h, --help           print this help and exit""");
    }

    private static String version() {
        String v = Main.class.getPackage().getImplementationVersion();
        return v != null ? v : "0.1.0";
    }
}
