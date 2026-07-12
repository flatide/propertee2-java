package com.flatide.propertee2.interpreter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.flatide.propertee2.core.ScriptParser;
import com.flatide.propertee2.parser.ProperTeeParser;
import com.flatide.propertee2.platform.DefaultPlatformProvider;
import com.flatide.propertee2.scheduler.Scheduler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Covers v1 HTTP builtins through the same façade path TeeBox uses. */
class FacadeHttpBuiltinTest {

    @Test
    void httpBuiltinsCoverGetPostStatusAndTransportFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hello", exchange -> respond(exchange, 200, "hello-body"));
        server.createContext("/echo", exchange -> respond(exchange, 201, "got:" + readBody(exchange)));
        server.createContext("/notfound", exchange -> respond(exchange, 404, "nope"));
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();

        List<String> out = new ArrayList<>();
        try {
            run("""
                    g = HTTP_GET("%s/hello")
                    PRINT("GET", g.ok, g.value.status, g.value.body)
                    p = HTTP_POST("%s/echo", "payload42")
                    PRINT("POST", p.ok, p.value.status, p.value.body)
                    nf = HTTP_GET("%s/notfound")
                    PRINT("NF", nf.ok, nf.value.status)
                    x = HTTP("GET", "%s/hello")
                    PRINT("GEN", x.ok, x.value.status)
                    bad = HTTP_GET("http://127.0.0.1:1/nope")
                    PRINT("BAD", bad.ok, bad.value.status, LEN(bad.value.body) > 0)
                    """.formatted(base, base, base, base), out);
        } finally {
            server.stop(0);
        }

        assertEquals("GET true 200 hello-body", out.get(0));
        assertEquals("POST true 201 got:payload42", out.get(1));
        assertEquals("NF false 404", out.get(2));
        assertEquals("GEN true 200", out.get(3));
        assertEquals("BAD false 0 true", out.get(4));
    }

    @Test
    void postObjectBodyIsJsonAndHeaderAliasIsAccepted() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws java.io.IOException {
                String reply = "body=" + readBody(exchange)
                        + "|ct=" + exchange.getRequestHeaders().getFirst("Content-type")
                        + "|runId=" + exchange.getRequestHeaders().getFirst("RUN-ID");
                respond(exchange, 200, reply);
            }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();

        List<String> out = new ArrayList<>();
        try {
            run("""
                    test = {"test": "ok"}
                    opts = {"header": {"Content-type": "application/json", "RUN-ID": "r123"}}
                    res = HTTP_POST("%s/echo", test, opts)
                    PRINT(res.ok, res.value.body)
                    """.formatted(base), out);
        } finally {
            server.stop(0);
        }

        assertEquals("true body={\"test\":\"ok\"}|ct=application/json|runId=r123", out.get(0));
    }

    private static void run(String script, List<String> out) {
        BuiltinFunctions.PrintFunction sink = args -> out.add(join(args));
        List<String> errors = new ArrayList<>();
        ProperTeeParser.RootContext tree = ScriptParser.parse(script, errors);
        assertNotNull(tree, "parse failed: " + errors);
        BuiltinFunctions builtins = new BuiltinFunctions(sink, sink, null, null, new DefaultPlatformProvider());
        ProperTeeInterpreter visitor =
                new ProperTeeInterpreter(new LinkedHashMap<>(), sink, sink, 1000, "error", builtins);
        try {
            new Scheduler(visitor).run(visitor.createRootStepper(tree));
        } finally {
            builtins.shutdown();
        }
    }

    private static String join(Object[] args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }

    private static String readBody(HttpExchange exchange) throws java.io.IOException {
        try (InputStream in = exchange.getRequestBody()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            int read;
            while ((read = in.read(chunk)) != -1) {
                out.write(chunk, 0, read);
            }
            return out.toString("UTF-8");
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes("UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
