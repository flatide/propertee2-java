package com.flatide.propertee2.host;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.flatide.propertee2.interp.Engine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Deferred file-read failures must become Result.error, not a leaked runtime exception. */
class FileIoErrorTest {

    @Test void readLinesOnMalformedUtf8ReturnsErrorResult() throws IOException {
        Path dir = Files.createTempDirectory("propertee2-utf8-");
        try {
            Path bad = dir.resolve("bad.bin");
            // 0xC3 starts a 2-byte UTF-8 sequence but 0x28 is not a valid continuation byte.
            // Files.lines() decodes lazily, so this throws (wrapped in UncheckedIOException) during READ_LINES.
            Files.write(bad, new byte[]{(byte) 0xC3, (byte) 0x28});

            String out = new Engine().run(
                    "res = READ_LINES(badFile)\nPRINT(res.ok)\n",
                    Map.of("badFile", bad.toString()));

            assertEquals("false\n", out);   // before the fix this leaked an UncheckedIOException
        } finally {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    /** READ_JSON_FILE (spec v0.18.0) error corners the fixture can't pin byte-stably. */
    @Test void readJsonFileErrorsAndBomHandling() throws IOException {
        Path dir = Files.createTempDirectory("propertee2-readjson-");
        try {
            // A leading UTF-8 BOM (Windows editors add one) is ignored — RFC 8259 permits it.
            Path bom = dir.resolve("bom.json");
            Files.writeString(bom, "\uFEFF{\"k\": 7}");
            assertEquals("7\n", new Engine().run(
                    "PRINT(READ_JSON_FILE(f).value.k)\n", Map.of("f", bom.toString())));

            // Missing file: an error Result whose value is the read failure's message (contains the path).
            Path missing = dir.resolve("nope.json");
            String out = new Engine().run(
                    "res = READ_JSON_FILE(f)\nPRINT(res.ok)\nPRINT(CONTAINS(res.value, \"nope.json\"))\n",
                    Map.of("f", missing.toString()));
            assertEquals("false\ntrue\n", out);

            // Invalid JSON: the parser's message, same as JSON_PARSE would report.
            Path broken = dir.resolve("broken.json");
            Files.writeString(broken, "{\"a\": }");
            String parseOut = new Engine().run(
                    "res = READ_JSON_FILE(f)\nPRINT(res.ok)\nPRINT(STARTS_WITH(res.value, \"Invalid JSON\"))\n",
                    Map.of("f", broken.toString()));
            assertEquals("false\ntrue\n", parseOut);
        } finally {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }
}
