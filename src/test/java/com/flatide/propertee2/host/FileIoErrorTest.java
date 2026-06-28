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
}
