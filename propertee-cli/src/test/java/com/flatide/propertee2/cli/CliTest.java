package com.flatide.propertee2.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CliTest {

    private String runMain(String[] args) throws IOException {
        PrintStream original = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try {
            Main.main(args);
        } finally {
            System.setOut(original);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    @Test void runsScriptFile() throws IOException {
        Path f = Files.createTempFile("cli", ".tee");
        Files.writeString(f, "x = 10\nPRINT(x + 5)\n");
        try {
            assertEquals("15\n", runMain(new String[]{f.toString()}));
        } finally {
            Files.deleteIfExists(f);
        }
    }

    @Test void injectsPropsViaDashP() throws IOException {
        Path f = Files.createTempFile("cli", ".tee");
        Files.writeString(f, "PRINT(width * height)\n");
        try {
            assertEquals("20000\n", runMain(new String[]{"-p", "{\"width\":100,\"height\":200}", f.toString()}));
        } finally {
            Files.deleteIfExists(f);
        }
    }

    @Test void printsVersion() throws IOException {
        assertEquals("propertee2 0.1.0\n", runMain(new String[]{"--version"}));
    }

    // ---- 0.34.0: --result-only ----------------------------------------------------------------

    private record Run(int code, String out, String err) {}

    private Run runScript(String source, boolean resultOnly) throws IOException {
        Path f = Files.createTempFile("cli", ".tee");
        Files.writeString(f, source);
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        try {
            int code = Main.run(f.toString(), java.util.Map.of(), java.util.List.of(), null, resultOnly,
                    new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                    new PrintStream(errBuf, true, StandardCharsets.UTF_8));
            return new Run(code, outBuf.toString(StandardCharsets.UTF_8), errBuf.toString(StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(f);
        }
    }

    @Test void resultOnlyPrintsJustTheResultAsJson() throws IOException {
        Run r = runScript("PRINT(\"noise\")\nreturn {\"ok\": true, \"s\": \"a\\\"b\", \"z\": null}\n", true);
        assertEquals(new Run(0, "{\"ok\":true,\"s\":\"a\\\"b\",\"z\":null}\n", ""), r);
        // without a return: the final `result` global, else {}
        assertEquals("[1,2]\n", runScript("PRINT(1)\nresult = [1, 2]\n", true).out());
        assertEquals("{}\n", runScript("PRINT(1)\n", true).out());
    }

    @Test void resultOnlyKeepsStderrDiagnostics() throws IOException {
        Run r = runScript("function bad() do\n    ::x = 1\nend\nmulti m do\n    thread w: bad()\nend\n"
                + "PRINT(\"noise\")\nreturn m.w.ok\n", true);
        assertEquals(0, r.code());
        assertEquals("false\n", r.out());
        assertEquals(true, r.err().startsWith("[THREAD ERROR] Runtime Error at line 2:4: Cannot assign"));
    }

    @Test void resultOnlyRuntimeErrorGoesToStderrWithExit1() throws IOException {
        Run r = runScript("PRINT(\"before\")\ny = nope + 1\nreturn 1\n", true);
        assertEquals(new Run(1, "",
                "Runtime error: Runtime Error at line 2:4: Variable 'nope' is not defined\n"), r);
    }

    @Test void resultOnlyRejectsANumberJsonCannotHold() throws IOException {
        // spec v0.32.0: stdout carries only JSON, which has no form for Infinity or NaN — and since
        // spec v0.36.0 a script cannot make one: the arithmetic fails, as any runtime error does
        assertEquals(new Run(1, "", "Runtime error: Runtime Error at line 2:7: Number overflow: the result is too large to represent\n"),
                runScript("PRINT(\"noise\")\nreturn TO_NUMBER(\"1e308\") * 10\n", true));
        assertEquals(new Run(0, "1.7976931348623157e+308\n", ""),
                runScript("return TO_NUMBER(\"1.7976931348623157e308\")\n", true));
    }

    @Test void defaultModeKeepsOutputAndInlineRuntimeErrors() throws IOException {
        // the conformance contract: no result echo; a runtime error is part of stdout, exit 0
        assertEquals(new Run(0, "hello\n", ""), runScript("PRINT(\"hello\")\nreturn true\n", false));
        assertEquals(new Run(0, "before\nRuntime error: Runtime Error at line 2:4: Variable 'nope' is not defined\n", ""),
                runScript("PRINT(\"before\")\ny = nope + 1\n", false));
    }

    @Test void shellWorksInScriptMode() throws IOException {
        // 0.19.0: the CLI wires an in-process SimpleTaskRunner (the bare Engine still errors)
        Path f = Files.createTempFile("cli", ".tee");
        Files.writeString(f, "r = SHELL(\"echo from-cli\")\nPRINT(r.ok)\nPRINT(r.value)\n");
        try {
            assertEquals("true\nfrom-cli\n", runMain(new String[]{f.toString()}));
        } finally {
            Files.deleteIfExists(f);
        }
    }
}
