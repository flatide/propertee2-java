package com.flatide.propertee2.cli;

import com.flatide.propertee2.Parsing;
import com.flatide.propertee2.interp.DebugCallSite;
import com.flatide.propertee2.interp.DebugFrame;
import com.flatide.propertee2.interp.DebugQuit;
import com.flatide.propertee2.interp.Engine;
import com.flatide.propertee2.task.SimpleTaskRunner;
import com.flatide.propertee2.value.JsonParser;
import com.flatide.propertee2.value.TeeError;
import com.flatide.propertee2.value.TeeFormat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * {@code propertee2 --dap}: a Debug Adapter Protocol server over stdio (0.24.0), the bridge that
 * gives the VS Code extension real breakpoints and stepping on top of the 0.22.0/0.23.0 debugger
 * hook. VS Code spawns this process per debug session and speaks DAP to it directly — no
 * JavaScript adapter in between, so the whole debugger is testable as plain Java ({@code DapTest}).
 *
 * <p>Threading: the main thread reads framed DAP requests; the script runs on a daemon thread
 * whose {@code DebugHandler} blocks on a command queue at each break. Because the paused frame is
 * only valid on the script thread (the interpreter's state is fiber-confined via ScopedValue),
 * every frame access — stack, scopes, variables, evaluate — is shipped to that thread as a
 * {@link Query} and answered through a future. Resume commands ({@link Resume}) end the break,
 * arming a step first when asked.
 *
 * <p>Kept minimal by design: one thread ("main"), flat variables (values in display form),
 * breakpoints verified optimistically (a line with no statement simply never fires), no pause
 * request (a running script is interrupted only by disconnect, which ends the process — the
 * adapter owns it). {@code noDebug} runs without a handler: breakpoints and {@code debug}
 * statements are no-ops, exactly like plain script mode.
 */
final class Dap {

    private sealed interface Cmd permits Resume, Query {}
    private record Resume(String kind) implements Cmd {}
    private record Query(Function<DebugFrame, Object> fn, CompletableFuture<Object> result) implements Cmd {}

    private final InputStream in;
    private final OutputStream out;
    private final SimpleTaskRunner taskRunner = new SimpleTaskRunner();
    private final BlockingQueue<Cmd> cmds = new LinkedBlockingQueue<>();

    private int seq = 1;
    private Engine.Session session;
    private Map<String, Object> launchArgs;
    private String programPath;
    private final List<Integer> pendingBreakpoints = new ArrayList<>();
    private boolean launched, configured, started;
    private volatile boolean paused;
    private volatile boolean exitAfterReply;

    Dap(InputStream in, OutputStream out) {
        this.in = in;
        this.out = out;
    }

    /** Read-dispatch loop; returns at EOF or after a disconnect/terminate reply. */
    void run() throws IOException {
        try {
            Map<String, Object> message;
            while ((message = readMessage()) != null) {
                if ("request".equals(message.get("type"))) dispatch(message);
                if (exitAfterReply) return;
            }
        } finally {
            taskRunner.shutdown();
        }
    }

    // ---- request dispatch ----------------------------------------------------------------

    private void dispatch(Map<String, Object> request) {
        String command = String.valueOf(request.get("command"));
        Map<String, Object> args = asMap(request.get("arguments"));
        try {
            switch (command) {
                case "initialize" -> {
                    respond(request, map(
                            "supportsConfigurationDoneRequest", true,
                            "supportsEvaluateForHovers", true,
                            "supportsTerminateRequest", true));
                    event("initialized", map());
                }
                case "launch" -> {
                    launchArgs = args;
                    programPath = args.get("program") instanceof String p ? p : null;
                    if (programPath == null) {
                        respond(request, null, false, "No \"program\" set in the launch configuration.");
                        return;
                    }
                    openSession();
                    launched = true;
                    respond(request, map());
                    maybeStart();
                }
                case "setBreakpoints" -> {
                    List<Integer> lines = new ArrayList<>();
                    for (Object bp : asList(args.get("breakpoints"))) {
                        Object line = asMap(bp).get("line");
                        if (line instanceof Integer l) lines.add(l);
                    }
                    setBreakpoints(lines);
                    List<Object> verified = new ArrayList<>();
                    for (Integer l : lines) verified.add(map("verified", true, "line", l));
                    respond(request, map("breakpoints", verified));
                }
                case "setExceptionBreakpoints", "setFunctionBreakpoints" -> respond(request, map());
                case "configurationDone" -> {
                    configured = true;
                    respond(request, map());
                    maybeStart();
                }
                case "threads" -> respond(request, map("threads", List.of(map("id", 1, "name", "main"))));
                case "stackTrace" -> {
                    List<?> frames = paused ? (List<?>) query(this::buildStack) : List.of();
                    respond(request, map("stackFrames", frames, "totalFrames", frames.size()));
                }
                case "scopes" -> respond(request, map("scopes", List.of(
                        map("name", "Locals", "variablesReference", 1001, "expensive", false),
                        map("name", "Globals", "variablesReference", 1002, "expensive", false))));
                case "variables" -> {
                    int ref = args.get("variablesReference") instanceof Integer r ? r : 0;
                    List<?> vars = paused
                            ? (List<?>) query(f -> renderVars(ref == 1002 ? f.globals() : f.locals()))
                            : List.of();
                    respond(request, map("variables", vars));
                }
                case "evaluate" -> {
                    if (!paused) {
                        respond(request, null, false, "not paused");
                        return;
                    }
                    String expr = String.valueOf(args.get("expression"));
                    try {
                        Object value = query(f -> f.eval(expr));
                        respond(request, map(
                                "result", value == null ? "" : TeeFormat.display(value),
                                "variablesReference", 0));
                    } catch (RuntimeException e) {
                        respond(request, null, false, evalError(e));
                    }
                }
                case "continue" -> {
                    resume("continue");
                    respond(request, map("allThreadsContinued", true));
                }
                case "next" -> { resume("next"); respond(request, map()); }
                case "stepIn" -> { resume("stepIn"); respond(request, map()); }
                case "stepOut" -> { resume("stepOut"); respond(request, map()); }
                case "pause" -> respond(request, null, false,
                        "pause is not supported — a ProperTee run breaks at debug statements, breakpoints, and steps");
                case "disconnect", "terminate" -> {
                    respond(request, map());
                    exitAfterReply = true;      // the process ends; a still-running script dies with it
                }
                default -> respond(request, map());
            }
        } catch (Exception e) {
            respond(request, null, false, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    // ---- session & script thread -----------------------------------------------------------

    private void openSession() {
        Map<String, Object> props = asMap(launchArgs.get("props"));
        session = new Engine().setTaskRunner(taskRunner).session(props,
                line -> output("stdout", line + "\n"),
                line -> output("stderr", line + "\n"));
        if (!Boolean.TRUE.equals(launchArgs.get("noDebug"))) {
            session.setDebugHandler(this::onBreak);
            setBreakpoints(new ArrayList<>(pendingBreakpoints));
        }
    }

    private void setBreakpoints(List<Integer> lines) {
        if (session == null) {                       // setBreakpoints before launch: stash
            pendingBreakpoints.clear();
            pendingBreakpoints.addAll(lines);
            return;
        }
        session.debugBreakpoints().clear();
        session.debugBreakpoints().addAll(lines);
    }

    private void maybeStart() {
        if (!launched || !configured || started) return;
        started = true;
        Thread script = new Thread(this::runScript, "propertee2-dap-script");
        script.setDaemon(true);
        script.start();
    }

    private void runScript() {
        int exitCode = 0;
        try {
            String source = Files.readString(Path.of(programPath), StandardCharsets.UTF_8);
            session.eval(source);
        } catch (IOException e) {
            output("stderr", "propertee2: cannot read " + programPath + ": " + e.getMessage() + "\n");
            exitCode = 1;
        } catch (Parsing.SyntaxException e) {
            output("stderr", "propertee2: " + programPath + ": " + e.getMessage() + "\n");
            exitCode = 1;
        } catch (TeeError e) {
            // script runtime error: normal output + exit 0, the CLI contract
            output("stdout", "Runtime error: " + e.positioned() + "\n");
        } catch (DebugQuit ignored) {
            // aborted from the debugger (disconnect while paused)
        }
        event("exited", map("exitCode", exitCode));
        event("terminated", map());
    }

    // ---- the break (script thread) -----------------------------------------------------------

    private void onBreak(DebugFrame frame) {
        paused = true;
        try {
            event("stopped", map(
                    "reason", frame.reason() == DebugFrame.Reason.STEP ? "step" : "breakpoint",
                    "description", frame.reason() == DebugFrame.Reason.DEBUG_STATEMENT
                            ? "debug statement" : ("paused: " + frame.statementText()),
                    "threadId", 1,
                    "allThreadsStopped", true));
            while (true) {
                Cmd cmd;
                try {
                    cmd = cmds.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (cmd instanceof Query q) {
                    try {
                        q.result().complete(q.fn().apply(frame));
                    } catch (Throwable t) {
                        q.result().completeExceptionally(t);
                    }
                    continue;
                }
                switch (((Resume) cmd).kind()) {
                    case "next"    -> { frame.stepOver(); return; }
                    case "stepIn"  -> { frame.stepInto(); return; }
                    case "stepOut" -> { frame.stepOut();  return; }
                    default        -> { return; }             // continue
                }
            }
        } finally {
            paused = false;
        }
    }

    /** Run {@code fn} against the paused frame on the script thread (frame state is fiber-confined). */
    private Object query(Function<DebugFrame, Object> fn) throws Exception {
        CompletableFuture<Object> result = new CompletableFuture<>();
        cmds.put(new Query(fn, result));
        try {
            return result.get(5, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof RuntimeException re ? re : e;
        }
    }

    private void resume(String kind) {
        cmds.add(new Resume(kind));
    }

    private Object buildStack(DebugFrame frame) {
        List<Object> frames = new ArrayList<>();
        List<DebugCallSite> stack = frame.callStack();               // outermost first
        String current = stack.isEmpty() ? "main" : stack.get(stack.size() - 1).function();
        frames.add(stackFrame(0, current, frame.line(), frame.column() + 1));
        for (int i = stack.size() - 1; i >= 0; i--) {
            DebugCallSite call = stack.get(i);
            String name = i == 0 ? "main" : stack.get(i - 1).function();
            frames.add(stackFrame(frames.size(), name, call.line(), call.column() + 1));
        }
        return frames;
    }

    private Map<String, Object> stackFrame(int id, String name, int line, int column) {
        return map("id", id, "name", name, "line", line, "column", column,
                "source", map("name", Path.of(programPath).getFileName().toString(), "path", programPath));
    }

    private static List<Object> renderVars(Map<String, Object> vars) {
        List<Object> out = new ArrayList<>();
        for (Map.Entry<String, Object> e : vars.entrySet()) {
            out.add(map("name", e.getKey(), "value", TeeFormat.display(e.getValue()), "variablesReference", 0));
        }
        return out;
    }

    private static String evalError(RuntimeException e) {
        if (e instanceof TeeError te) return te.positioned();
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    // ---- wire ---------------------------------------------------------------------------------

    private void respond(Map<String, Object> request, Map<String, Object> body,
                         boolean success, String message) {
        Map<String, Object> response = map(
                "type", "response",
                "request_seq", request.get("seq"),
                "command", request.get("command"),
                "success", success);
        if (message != null) response.put("message", message);
        if (body != null) response.put("body", body);
        send(response);
    }

    private void respond(Map<String, Object> request, Map<String, Object> body) {
        respond(request, body, true, null);
    }

    private void event(String name, Map<String, Object> body) {
        send(map("type", "event", "event", name, "body", body));
    }

    private void output(String category, String text) {
        event("output", map("category", category, "output", text));
    }

    private synchronized void send(Map<String, Object> message) {
        message.put("seq", seq++);
        byte[] body = TeeFormat.json(message).getBytes(StandardCharsets.UTF_8);
        try {
            out.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(body);
            out.flush();
        } catch (IOException e) {
            // the client is gone; the read loop will see EOF and end the process
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMessage() throws IOException {
        int length = -1;
        String header;
        while ((header = readHeaderLine()) != null && !header.isEmpty()) {
            if (header.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                length = Integer.parseInt(header.substring(15).trim());
            }
        }
        if (header == null || length < 0) return null;         // EOF / malformed: end the session
        byte[] body = in.readNBytes(length);
        if (body.length < length) return null;
        return (Map<String, Object>) JsonParser.parse(new String(body, StandardCharsets.UTF_8));
    }

    /** One header line, without the trailing CRLF; null at EOF before any byte. */
    private String readHeaderLine() throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return line.toString().stripTrailing();   // tolerate bare LF
            if (c != '\r') line.append((char) c);
        }
        return line.isEmpty() ? null : line.toString();
    }

    // ---- small builders -------------------------------------------------------------------

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
    }

    private static List<?> asList(Object v) {
        return v instanceof List<?> l ? l : List.of();
    }
}
