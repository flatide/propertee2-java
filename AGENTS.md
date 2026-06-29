# Repository Guidelines

## Project Structure & Module Organization

This repository is a pre-engine scaffold for the Java 25 ProperTee runtime. Core implementation code has not been created yet. Current assets are:

- `grammar/ProperTee.g4`: ANTLR4 grammar copied from v1; treat as compatibility-critical.
- `docs/`: canonical design and semantic contracts. Read `java25-vthread-runtime-design-ko.md`, `value-model-and-builtins.md`, and `conformance-tests.md` before runtime work.
- `src/test/resources/tests/`: 84 `.tee` inputs paired with 84 `.expected` outputs for v1 semantic conformance.
- `spike/`: throwaway Java cooperative-runtime prototype proving the scheduling model.

## Build, Test, and Development Commands

No Gradle skeleton or main test runner exists yet. Use the commands that are valid for the current scaffold:

```bash
spike/run.sh
```

Compiles and runs the cooperative-runtime spike without preview flags.

```bash
spike/run.sh sts
```

Checks whether `StructuredTaskScope` still requires preview flags on the active JDK.

```bash
cd src/test/resources/tests && for f in *.tee; do [ -f "${f%.tee}.expected" ] || echo "MISSING: ${f%.tee}.expected"; done
```

Verifies fixture-pair integrity.

## Coding Style & Naming Conventions

Target Java 25 stable APIs only: virtual threads and `ScopedValue` are allowed; preview dependencies are not. Keep Java naming conventional: `PascalCase` classes, `camelCase` methods/fields, uppercase constants. Use 4-space indentation. Preserve the recursive tree-walk runtime direction and the single-baton cooperative model described in `CLAUDE.md`.

## Testing Guidelines

Conformance fixtures are the source of truth. Do not edit `.expected` files to fit new behavior; change runtime behavior to match them byte-for-byte, including output order and error strings. Name new fixtures with the existing numeric prefix pattern, for example `87_feature_name.tee` and `87_feature_name.expected`.

## Commit & Pull Request Guidelines

Existing history uses short, descriptive subjects such as `Add CLAUDE.md: project context + confirmed decisions for fresh sessions`. Prefer imperative, focused commit subjects and mention compatibility or fixture impact in the body when relevant. PRs should describe the runtime phase affected, list commands run, and call out any semantic-contract changes. Link related issues or design sections when applicable.

## Agent-Specific Instructions

Before making substantial changes, read the three starred docs listed in `CLAUDE.md`. Never rewrite copied v1 assets casually; grammar, fixtures, error text, and value formatting are compatibility baselines.
