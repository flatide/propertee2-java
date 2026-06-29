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
}
