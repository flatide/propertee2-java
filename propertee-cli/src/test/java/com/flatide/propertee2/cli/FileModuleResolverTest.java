package com.flatide.propertee2.cli;

import com.flatide.propertee2.module.ModuleRequest;
import com.flatide.propertee2.module.ModuleResolutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileModuleResolverTest {
    @TempDir Path temp;

    @Test void unversionedChoosesHighestNumericVersion() throws IOException {
        Path util = Files.createDirectories(temp.resolve("util"));
        Files.writeString(util.resolve("file.2.tee"), "two");
        Files.writeString(util.resolve("file.10.tee"), "ten");
        Files.writeString(util.resolve("file.v99.tee"), "ignored");
        Files.writeString(util.resolve("file.tee"), "fallback");

        var source = new FileModuleResolver(temp).resolve(new ModuleRequest("util.file", null));
        assertEquals(10, source.version());
        assertEquals("ten", source.source());
    }

    @Test void unversionedFallsBackOnlyWhenNoVersionExists() throws IOException {
        Path util = Files.createDirectories(temp.resolve("util"));
        Files.writeString(util.resolve("file.tee"), "fallback");

        var source = new FileModuleResolver(temp).resolve(new ModuleRequest("util.file", null));
        assertEquals(null, source.version());
        assertEquals("fallback", source.source());
    }

    @Test void exactVersionDoesNotFallBack() throws IOException {
        Path util = Files.createDirectories(temp.resolve("util"));
        Files.writeString(util.resolve("file.tee"), "fallback");

        assertThrows(ModuleResolutionException.class,
                () -> new FileModuleResolver(temp).resolve(new ModuleRequest("util.file", 2)));
    }

    @Test void symlinkCannotEscapeRoot() throws IOException {
        Path outside = Files.createTempFile("propertee-module-outside", ".tee");
        Files.writeString(outside, "secret");
        Path util = Files.createDirectories(temp.resolve("util"));
        Files.createSymbolicLink(util.resolve("file.1.tee"), outside);

        assertThrows(ModuleResolutionException.class,
                () -> new FileModuleResolver(temp).resolve(new ModuleRequest("util.file", 1)));
    }
}
