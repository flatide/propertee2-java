package com.flatide.propertee2.cli;

import com.flatide.propertee2.value.TeeFormat;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@code propertee2 --dap} ({@link Dap}) as a DAP client over piped streams — the same
 * wire VS Code uses. Each test is a full session: initialize → launch → (breakpoints) →
 * configurationDone → stopped/step/continue → terminated.
 */
class DapTest {

    /**
     * A thread-agnostic in-memory pipe. {@code java.io.Piped*Stream} won't do here: it tracks the
     * <i>thread</i> that last wrote and throws "Write end dead" once it exits — and the DAP server
     * legitimately writes from two threads (responses from the reader, events from the script
     * thread, which dies after {@code terminated}).
     */
    private static final class Pipe {
        private static final byte[] EOF = new byte[0];
        private final java.util.concurrent.BlockingQueue<byte[]> chunks =
                new java.util.concurrent.LinkedBlockingQueue<>();
        private byte[] current = new byte[0];
        private int pos;

        final java.io.OutputStream sink = new java.io.OutputStream() {
            @Override public void write(int b) { chunks.add(new byte[]{(byte) b}); }
            @Override public void write(byte[] b, int off, int len) {
                chunks.add(java.util.Arrays.copyOfRange(b, off, off + len));
            }
            @Override public void close() { chunks.add(EOF); }
        };

        final java.io.InputStream source = new java.io.InputStream() {
            @Override public int read() {
                while (pos >= current.length) {
                    try {
                        byte[] next = chunks.poll(10, java.util.concurrent.TimeUnit.SECONDS);
                        if (next == null || next == EOF) return -1;
                        current = next;
                        pos = 0;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return -1;
                    }
                }
                return current[pos++] & 0xff;
            }
            @Override public int read(byte[] b, int off, int len) {
                if (len == 0) return 0;        // InputStream contract — readNBytes probes with len 0
                int first = read();
                if (first < 0) return -1;
                b[off] = (byte) first;
                int n = 1;
                while (n < len && pos < current.length) b[off + n++] = current[pos++];
                return n;
            }
        };
    }

    /** Minimal framed DAP client; collects output events on the side. */
    private static final class Client implements AutoCloseable {
        final Pipe clientToServer = new Pipe();
        final Pipe serverToClient = new Pipe();
        final java.io.OutputStream toServer = clientToServer.sink;
        final java.io.InputStream fromServer = serverToClient.source;
        final List<String> stdout = new ArrayList<>();
        final List<String> stderr = new ArrayList<>();
        int seq = 1;

        Client() throws IOException {
            Dap dap = new Dap(clientToServer.source, serverToClient.sink);
            Thread server = new Thread(() -> {
                try { dap.run(); } catch (IOException ignored) { }
            }, "dap-server");
            server.setDaemon(true);
            server.start();
        }

        void request(String command, Map<String, Object> args) throws IOException {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", seq++);
            m.put("type", "request");
            m.put("command", command);
            if (args != null) m.put("arguments", args);
            byte[] body = TeeFormat.json(m).getBytes(StandardCharsets.UTF_8);
            toServer.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            toServer.write(body);
            toServer.flush();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> read() throws IOException {
            int length = -1;
            StringBuilder line = new StringBuilder();
            int c;
            while ((c = fromServer.read()) != -1) {
                if (c == '\n') {
                    String header = line.toString().strip();
                    line.setLength(0);
                    if (header.isEmpty()) break;
                    if (header.toLowerCase().startsWith("content-length:")) {
                        length = Integer.parseInt(header.substring(15).strip());
                    }
                } else if (c != '\r') {
                    line.append((char) c);
                }
            }
            if (length < 0) throw new IOException("server stream ended");
            byte[] body = fromServer.readNBytes(length);
            return (Map<String, Object>) com.flatide.propertee2.value.JsonParser.parse(
                    new String(body, StandardCharsets.UTF_8));
        }

        /** Read messages (collecting output events) until one matches. */
        Map<String, Object> until(Predicate<Map<String, Object>> match) throws IOException {
            while (true) {
                Map<String, Object> m = read();
                if ("event".equals(m.get("type")) && "output".equals(m.get("event"))) {
                    Map<?, ?> body = (Map<?, ?>) m.get("body");
                    ("stderr".equals(body.get("category")) ? stderr : stdout)
                            .add(String.valueOf(body.get("output")));
                }
                if (match.test(m)) return m;
            }
        }

        Map<String, Object> response(String command) throws IOException {
            return until(m -> "response".equals(m.get("type")) && command.equals(m.get("command")));
        }

        Map<String, Object> event(String name) throws IOException {
            return until(m -> "event".equals(m.get("type")) && name.equals(m.get("event")));
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> body(Map<String, Object> m) {
            return (Map<String, Object>) m.getOrDefault("body", Map.of());
        }

        void start(String program, Map<String, Object> extraLaunchArgs, List<Integer> breakLines)
                throws IOException {
            request("initialize", Map.of());
            response("initialize");
            event("initialized");
            Map<String, Object> launch = new LinkedHashMap<>(extraLaunchArgs);
            launch.put("program", program);
            request("launch", launch);
            response("launch");
            if (breakLines != null) {
                List<Object> bps = new ArrayList<>();
                for (Integer l : breakLines) bps.add(Map.of("line", l));
                request("setBreakpoints", Map.of("source", Map.of("path", program), "breakpoints", bps));
                response("setBreakpoints");
            }
            request("configurationDone", Map.of());
            response("configurationDone");
        }

        @Override public void close() throws IOException {
            toServer.close();
        }
    }

    private static String tempScript(String source) throws IOException {
        Path f = Files.createTempFile("dap-test", ".tee");
        f.toFile().deleteOnExit();
        Files.writeString(f, source, StandardCharsets.UTF_8);
        return f.toString();
    }

    @SuppressWarnings("unchecked")
    @Test void breakpointHitExposesStackVariablesAndEvaluate() throws Exception {
        String program = tempScript("""
                function inner(n) do
                    x = n * 2
                    return x
                end
                a = 1
                b = inner(a)
                PRINT("b:", b)
                """);
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            try (Client c = new Client()) {
                c.start(program, Map.of(), List.of(2));

                Map<String, Object> stopped = Client.body(c.event("stopped"));
                assertEquals("breakpoint", stopped.get("reason"));

                c.request("stackTrace", Map.of("threadId", 1));
                List<Map<String, Object>> frames =
                        (List<Map<String, Object>>) Client.body(c.response("stackTrace")).get("stackFrames");
                assertEquals("inner", frames.get(0).get("name"));
                assertEquals(2, frames.get(0).get("line"));
                assertEquals("main", frames.get(1).get("name"));
                assertEquals(6, frames.get(1).get("line"));

                c.request("variables", Map.of("variablesReference", 1001));
                List<Map<String, Object>> locals =
                        (List<Map<String, Object>>) Client.body(c.response("variables")).get("variables");
                assertEquals(List.of("n"), locals.stream().map(v -> v.get("name")).toList());
                assertEquals("1", locals.get(0).get("value"));

                c.request("variables", Map.of("variablesReference", 1002));
                List<Map<String, Object>> globals =
                        (List<Map<String, Object>>) Client.body(c.response("variables")).get("variables");
                assertTrue(globals.stream().anyMatch(v -> v.get("name").equals("a")), globals.toString());

                c.request("evaluate", Map.of("expression", "n * 10", "context", "repl"));
                assertEquals("10", Client.body(c.response("evaluate")).get("result"));

                c.request("continue", Map.of("threadId", 1));
                c.response("continue");
                c.event("terminated");
                assertEquals(List.of("b: 2\n"), c.stdout);
                assertEquals(List.of(), c.stderr);
            }
        });
    }

    @Test void debugStatementStopsAndNextSteps() throws Exception {
        String program = tempScript("a = 1\ndebug\na = a + 1\nPRINT(a)\n");
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            try (Client c = new Client()) {
                c.start(program, Map.of(), null);

                Map<String, Object> stopped = Client.body(c.event("stopped"));
                assertEquals("breakpoint", stopped.get("reason"));
                assertEquals("debug statement", stopped.get("description"));

                c.request("next", Map.of("threadId", 1));
                c.response("next");
                Map<String, Object> stepStop = Client.body(c.event("stopped"));
                assertEquals("step", stepStop.get("reason"));

                c.request("stackTrace", Map.of("threadId", 1));
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> frames =
                        (List<Map<String, Object>>) Client.body(c.response("stackTrace")).get("stackFrames");
                assertEquals(3, frames.get(0).get("line"));

                c.request("continue", Map.of("threadId", 1));
                c.response("continue");
                c.event("terminated");
                assertEquals(List.of("2\n"), c.stdout);
            }
        });
    }

    @SuppressWarnings("unchecked")
    @Test void workerDebugStopExposesWorkerThreadAndMonitorDoesNotStop() throws Exception {
        String program = tempScript("""
                function worker(n) do
                    debug
                    return n
                end
                multi r do
                    thread job: worker(7)
                monitor 1
                    debug
                end
                PRINT(r.job.value)
                """);
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            try (Client c = new Client()) {
                c.start(program, Map.of(), null);

                Map<String, Object> stopped = Client.body(c.event("stopped"));
                assertEquals(2, stopped.get("threadId"));       // core worker 1 -> DAP thread 2

                c.request("threads", Map.of());
                List<Map<String, Object>> threads =
                        (List<Map<String, Object>>) Client.body(c.response("threads")).get("threads");
                assertEquals(List.of(1, 2), threads.stream().map(t -> t.get("id")).toList());
                assertTrue(String.valueOf(threads.get(1).get("name")).contains("worker"));

                c.request("stackTrace", Map.of("threadId", 2));
                List<Map<String, Object>> frames =
                        (List<Map<String, Object>>) Client.body(c.response("stackTrace")).get("stackFrames");
                assertEquals("worker", frames.get(0).get("name"));
                assertEquals(2, frames.get(0).get("line"));

                c.request("variables", Map.of("variablesReference", 1001));
                List<Map<String, Object>> locals =
                        (List<Map<String, Object>>) Client.body(c.response("variables")).get("variables");
                assertEquals("7", locals.stream().filter(v -> v.get("name").equals("n"))
                        .findFirst().orElseThrow().get("value"));

                c.request("continue", Map.of("threadId", 2));
                c.response("continue");
                c.event("terminated");                         // monitor's debug must not produce another stop
                assertEquals(List.of("7\n"), c.stdout);
            }
        });
    }

    @Test void noDebugIgnoresBreakpointsAndDebugStatements() throws Exception {
        String program = tempScript("debug\nx = 1\nPRINT(x)\n");
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            try (Client c = new Client()) {
                c.start(program, Map.of("noDebug", true), List.of(2));
                c.event("terminated");                     // no stopped event on the way
                assertEquals(List.of("1\n"), c.stdout);
            }
        });
    }

    @Test void launchArgsBecomeARGS() throws Exception {
        String program = tempScript("PRINT(LEN(_ARGS), _ARGS.1, _ARGS.2)\n");
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            try (Client c = new Client()) {
                c.start(program, Map.of("args", List.of("alpha", "42")), null);
                c.event("terminated");
                assertEquals(List.of("2 alpha 42\n"), c.stdout);
            }
        });
    }

    @Test void runtimeErrorIsNormalOutputWithExitZero() throws Exception {
        String program = tempScript("x = 1 / 0\n");
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            try (Client c = new Client()) {
                c.start(program, Map.of(), null);
                Map<String, Object> exited = Client.body(c.event("exited"));
                assertEquals(0, exited.get("exitCode"));   // the CLI contract: script errors are output
                c.event("terminated");
                assertTrue(c.stdout.get(0).startsWith("Runtime error: Runtime Error at line 1:"), c.stdout.toString());
            }
        });
    }
}
