# Repository Guidelines

## Project Structure & Module Organization

Two Gradle modules on a JDK 25 toolchain (see `CLAUDE.md` for the full map and status):

- `propertee-core/`: the engine — ANTLR grammar, `value/` model, `coop/` cooperative runtime, `interp/` recursive interpreter, `builtin/` catalog, plus the v1-API compat layer (`com.flatide.propertee2.{core,interpreter,platform,runtime,scheduler,task}` — renamed from the bare `com.flatide.*` in 0.15.0) that TeeBox consumes.
- `propertee-cli/`: the `propertee2` command (fat jar via `./gradlew dist`).
- `propertee-core/grammar/ProperTee.g4`: ANTLR4 grammar copied from v1; treat as compatibility-critical. The visitor is generated into `com.flatide.propertee2.parser` (since 0.15.0; it carried the v1 name `com.flatide.parser` before).
- `docs/`: canonical design and semantic contracts. Read `java25-vthread-runtime-design-ko.md`, `value-model-and-builtins.md`, and `conformance-tests.md` before runtime work.
- `propertee-core/src/test/resources/tests/`: `.tee` inputs paired with `.expected` outputs for semantic conformance (118 pairs as of 0.15.0 — the authoritative list is `conformance/Fixtures.java`; the count grows with spec batches).
- `spike/`: throwaway cooperative-runtime prototype that proved the scheduling model (historical; `spike/run.sh`).

## Build, Test, and Development Commands

```bash
./gradlew build                 # compile + all tests, both modules (no preview flags)
./gradlew :propertee-core:test  # engine tests only
./gradlew test --tests 'com.flatide.propertee2.conformance.ConformanceTest'   # the full fixture suite (count: conformance/Fixtures.java)
./gradlew dist                  # -> dist/propertee2-<version>.jar (run with java -jar, JDK 25)
```

The toolchain JDK is registered machine-locally in `~/.gradle/gradle.properties` (`org.gradle.java.installations.paths`).

## Coding Style & Naming Conventions

Target Java 25 stable APIs only: virtual threads and `ScopedValue` are allowed; preview dependencies are not. Keep Java naming conventional: `PascalCase` classes, `camelCase` methods/fields, uppercase constants. Use 4-space indentation. Preserve the recursive tree-walk runtime direction and the single-baton cooperative model described in `CLAUDE.md`.

## Testing Guidelines

Conformance fixtures are the source of truth. Do not edit `.expected` files to fit new behavior; change runtime behavior to match them byte-for-byte, including output order and error strings. Name new fixtures with the existing numeric prefix pattern, for example `87_feature_name.tee` and `87_feature_name.expected`, and register them in `conformance/Fixtures`.

## Commit & Pull Request Guidelines

Existing history uses short, descriptive subjects such as `Restore v1 HTTP builtins (HTTP_GET/HTTP_POST/HTTP) in the propertee2 catalog`. Prefer imperative, focused commit subjects and mention compatibility or fixture impact in the body when relevant. PRs should describe the runtime phase affected, list commands run, and call out any semantic-contract changes. Link related issues or design sections when applicable.

## Agent-Specific Instructions

Before making substantial changes, read the three starred docs listed in `CLAUDE.md`. Never rewrite copied v1 assets casually; grammar, fixtures, error text, and value formatting are compatibility baselines.
