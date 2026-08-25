package com.flatide.propertee2.cli;

import com.flatide.propertee2.Parsing;
import com.flatide.propertee2.interp.Engine;
import com.flatide.propertee2.module.ModuleResolutionException;
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
        Path moduleRoot = null;
        java.util.List<String> scriptArgs = new java.util.ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (file != null) {
                // Everything after the script goes to the script as _ARGS (spec v0.20.0) —
                // option parsing stops at the file, like python.
                scriptArgs.add(a);
                continue;
            }
            switch (a) {
                case "-h", "--help" -> { usage(System.out); return; }
                case "-v", "--version" -> { System.out.println("propertee2 " + version()); return; }
                case "-p", "--props" -> {
                    if (i + 1 >= args.length) fail("missing JSON object after " + a);
                    props = parseProps(args[++i]);
                }
                case "-i", "--interactive" -> interactive = true;
                case "--module-root" -> {
                    if (i + 1 >= args.length) fail("missing directory after --module-root");
                    moduleRoot = Path.of(args[++i]);
                }
                case "--dap" -> {
                    // Debug Adapter Protocol server on stdio (0.24.0) — spawned by the VS Code
                    // extension; the script and its options arrive in the DAP launch request.
                    try {
                        new Dap(System.in, System.out).run();
                    } catch (IOException e) {
                        System.err.println("propertee2: --dap: " + e.getMessage());
                        System.exit(1);
                    }
                    return;
                }
                default -> {
                    if (a.startsWith("-")) fail("unknown option: " + a);
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
                Path resolvedModuleRoot = moduleRoot != null ? moduleRoot
                        : file != null ? Path.of(file).toAbsolutePath().normalize().getParent()
                        : Path.of(".");
                Repl.run(props, scriptArgs,
                        new java.io.BufferedReader(new java.io.InputStreamReader(System.in, StandardCharsets.UTF_8)),
                        System.out, System.err, System.console() != null, version(), file,
                        resolvedModuleRoot);
            } catch (ModuleResolutionException e) {
                System.err.println("propertee2: " + e.getMessage());
                System.exit(1);
            } catch (IOException e) {
                System.err.println("propertee2: " + e.getMessage());
                System.exit(1);
            }
            return;
        }
        run(file, props, scriptArgs, moduleRoot);
    }

    private static void run(String file, Map<String, Object> props, java.util.List<String> scriptArgs,
                            Path configuredModuleRoot) {

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
            Path entry = Path.of(file).toAbsolutePath().normalize();
            Path moduleRoot = configuredModuleRoot != null ? configuredModuleRoot : entry.getParent();
            new Engine().setTaskRunner(taskRunner).setArgs(scriptArgs)
                    .setSourceId(entry.toString())
                    .setModuleResolver(new FileModuleResolver(moduleRoot))
                    .run(source, props, System.out::println, System.err::println);
        } catch (Parsing.SyntaxException e) {
            // malformed input: a concise stderr line + nonzero exit (a script *runtime* error is in stdout)
            System.err.println("propertee2: " + file + ": " + e.getMessage());
            System.exit(1);
        } catch (TeeError e) {
            // script runtime error: part of normal output (stdout, exit 0) — the conformance contract
            System.out.println("Runtime error: " + e.positioned());
        } catch (ModuleResolutionException e) {
            System.err.println("propertee2: " + e.getMessage());
            System.exit(1);
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
                usage: propertee2 [-p <json-object>] [--module-root <dir>] [-i] [script.tee] [args...]

                  With no script file, starts an interactive REPL on stdin. In the REPL
                  (and under -i) a `debug` statement pauses the run and opens a pdb-style
                  debug> prompt in the paused scope.

                  Arguments after the script are passed to it as the _ARGS
                  string array (option parsing stops at the script path).

                  -p, --props <json>   host-injected built-in properties (a JSON object)
                  -i, --interactive    run the script in a REPL session, then stay in the
                                       REPL with its state (debug statements break)
                  --module-root <dir>  root used to resolve imported module paths (defaults
                                       to the entry file's directory, or the current directory)
                  --dap                Debug Adapter Protocol server on stdio (for IDEs;
                                       the script arrives via the DAP launch request)
                  -v, --version        print version and exit
                  -h, --help           print this help and exit""");
    }

    private static String version() {
        String v = Main.class.getPackage().getImplementationVersion();
        return v != null ? v : "0.1.0";
    }
}
