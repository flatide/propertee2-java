# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`propertee2-java` is a **fully-cooperative runtime** for the [ProperTee](https://github.com/flatide/ProperTee) language. Its goal is to fundamentally resolve the eager seams left by the frozen [`propertee-java`](https://github.com/flatide/propertee-java) **v1.0.0** (Java 7/8, stepper-based) using **Java 21 virtual-thread (Project Loom) coroutines (Strategy B)**. **TeeBox** will consume this runtime once it stabilizes.

> **Status: 0.1.0 — feature-complete, full conformance.** All design phases (spike → PA–PF) are done. The engine passes **all 84 `.tee/.expected` fixtures** (byte-for-byte, deterministic, no flakiness; ~222 tests green): JDK 25 Gradle build + ANTLR codegen, the `value/` model, the full builtin catalog (incl. host-gated ENV/file I/O and `SHELL`/`SHELL_CTX`), a recursive tree-walk interpreter (`interp/`), the **production Coop cooperative runtime** (`coop/`, `ScopedValue`) with the program as the root fiber + the `Coop.blocking` host contract, **`multi`/`thread`/`monitor`**, external-function registration, keyword/function hiding, an `Engine` embedding API, and a `propertee2` CLI. Post-1.0: real `SHELL`/HTTP/Task execution, the `StructuredTaskScope` swap (when it leaves preview), a `core`/`cli` module split. Spike writeup: `docs/spike-findings.md`.

## Locked-in core decisions (agreed in a prior session — changing them requires justification)

1. **Two diverging lines.** v1.0.0 (Java 7/8) is **frozen** for the legacy expression-evaluator server (security/bugfixes only). All new features and full cooperativization happen in this repo (the modern runtime). The two codebases are **not synchronized** (one-way freeze).
2. **Baseline = Java 25 LTS, stable APIs only.** Uses virtual threads (final) + `ScopedValue` (JEP 506, final in 25). **`StructuredTaskScope` is still preview in 25 (JEP 505, 5th preview), so avoid it** — `multi` is hand-rolled with `newVirtualThreadPerTaskExecutor` + direct fork/join (encapsulated so it can be swapped locally once STS is finalized). → **zero preview dependencies.** (Before starting, empirically verify on a real JDK 25 whether STS is still preview by compiling.)
3. **Execution model = single-baton vthread coroutine.** One logical thread = one vthread, with a baton (semaphore / park-unpark) ensuring **only one runs at a time** → preserves the existing purity, lock-free property, and determinism. The call stack itself is the continuation.
4. **⚠️ Invariant — "no blocking while holding the baton."** Blocking while holding the baton halts the entire cooperative scheduler (a bigger risk than pinning). **Every potential blocking point (SLEEP / host I/O / external functions / spawn join) must honor the `Coop.*` primitive contract of "release baton → block → re-acquire."** Enforce this contract through the host-external registration API.
5. **Restore the interpreter as a recursive tree-walk.** Delete v1's stepper / `SchedulerCommand` / `AsyncPendingException` replay machine. But the **scheduler is repurposed, not deleted** (baton, state machine, wake timer, monitor tick, result collection are retained).
6. **Value semantics must not differ from v1 by a single byte.** The only thing that changes is **scheduling**. Using `Coop.blocking` to eliminate async statement-replay also fixes the "leading side-effects executed twice" correctness issue.
7. **Deterministic round-robin ordering must be pinned** — otherwise the copied-over `.expected` files (output-order sensitive) will flap.

## Repository structure (current)

Two Gradle modules (design §10), matching v1's `propertee-core` / `propertee-cli` / `dist` layout:

```
settings.gradle / build.gradle            # root: subproject config (JDK 25 toolchain) + `dist` task
propertee-core/                           # the engine (TeeBox depends on this; no CLI)
  grammar/ProperTee.g4                    #   grammar (identical to v1, unchanged). ANTLR4.
  src/main/java/com/flatide/propertee2/   #   value/ coop/ host/ builtin/ interp/ + Parsing.java
  src/test/java + src/test/resources/tests/  #   unit + conformance tests; 84 .tee/.expected fixtures
propertee-cli/                            # the `propertee2` command (application plugin, fat jar)
  src/main/java/com/flatide/propertee2/cli/Main.java
dist/                                     # `./gradlew dist` -> dist/propertee2-<version>.jar (java -jar)
spike/                                    # throwaway cooperative-model PoC (standalone, not a module)
docs/
  java25-vthread-runtime-design-ko.md     # ★Canonical design doc — model / Coop contract / preview decision / phases PA-PF / spike §10.1
  value-model-and-builtins.md             # ★Value-level semantic contract the new engine must reproduce
  conformance-tests.md                    # ★List of v1 semantic-equivalence tests + host-injection / interleaving notes
  LANGUAGE.md                             # Canonical language spec (copied from v1)
```

Read the three ★ docs above before starting any work.

## Spike — DONE (design §10.1, all 5 steps passed)

Validated in `spike/` (run `spike/run.sh`; full writeup in `docs/spike-findings.md`). On JDK 25.0.3, no preview flags; also builds/passes on JDK 21.

0. **STS still preview on JDK 25** (empirically: `javac` fails without `--enable-preview`) → hand-roll `multi`, zero preview deps. ✅
1. **Coop/scheduler** — baton, state machine, wake timer, deterministic round-robin (`ABCABCABC` stable over 30 runs). ✅
2. **Recursive-interpreter suspension** — `x = f()`, `a + f()`, `return f()` (sleep buried 2 frames deep) all overlap ~1x, not serial ~4x. ✅
3. **`Coop.blocking` removes async replay** — leading side-effect runs exactly once (v1 would run it twice). ✅
4. **`multi` result/monitor/purity** — `{status,ok,value}`, live monitor reads, global-write blocked. ✅

> The spike is throwaway (no ANTLR / real builtins / value formatting) — it validates *scheduling* only. It uses `ThreadLocal<Fiber>` for self; production swaps to `ScopedValue` (§5).

## Main implementation — PA–PE done (full conformance), PF next

- **PA done.** ✅ JDK 25 Gradle build (ANTLR visitor codegen, no preview flags), ✅ value model (`value/` — `TeeFormat` display+json, `Values`, `Result`, `TeeError`, `JsonParser`), ✅ parser facade + all-84-fixtures parse smoke test, ✅ full PURE builtin catalog (`builtin/Builtins` — math/type/string/string-matching/array+sort/object/JSON/timing) with return-type & error-message fidelity. HOST_GATED (`ENV`, file I/O) and BLOCKING (`SHELL`, `HTTP*`) builtins are deliberately deferred to PC/PD where they get the `Coop.blocking` contract.
- **PB done (sequential language).** ✅ Recursive tree-walk in `interp/` (`Engine`, `Interpreter`, `Ranges`, `Signals`, `UserFunction`) — instanceof-dispatch over the parse tree (no stepper/replay). Covers scopes (global/local/`::`), arithmetic+coercion, strict comparison/logic, 1-based access & dynamic keys, literals, ranges (BigDecimal float precision), control flow, functions, PRINT, pure builtins, escape processing, deepCopy/COW, and v1-exact errors (line:col). `ConformanceTest` runs the **46 sequential fixtures** byte-for-byte (the 38 multi/thread/SLEEP/monitor + host fixtures are the `PENDING` set, deferred to PC/PD).
- **PC done (core); host tail folds into PD.** ✅ Production Coop runtime in `coop/` (`Scheduler`/`Fiber`/`FiberState`/`Coop`) — ported from the hardened spike, deterministic round-robin, wake timer, `yield`/`sleep`/`blocking`, **`ScopedValue`** per-fiber context (§5). ✅ The interpreter runs as the **root fiber** (`Engine` → `coop.run`); `SLEEP` → `coop.sleep`; host-gated/blocking builtins route through `coop.blocking` (the §3.1 contract). ✅ `PlatformProvider` (`host/`) + `ENV` (`83`) + full file I/O (`85`, MKDIR/READ_LINES/WRITE_*/FILE_INFO/LIST_DIR/DELETE_FILE etc.). **Remaining (PC tail / fold into PD):** `SHELL`/HTTP (blocking), tasks, external-function registration (§3.1 `registerBlocking`), keyword/function hiding.
- **PD done.** ✅ `multi`/`thread`/`monitor` driving the scheduler's `spawn`/`awaitChildren`/`wake`: isolated setup phase, read-only global snapshot, worker fibers with statement-boundary interleaving + purity (`::`-write errors), `[THREAD ERROR]`/`[MONITOR ERROR]` reporting, `{status,ok,value}` collection (auto `#N` + dynamic keys, dup detection), and a monitor that ticks mid-run + once final (matches v1's tick counts, robust to timing).
- **Host tail done.** ✅ `SHELL`/`SHELL_CTX` (core-only "requires a host-provided TaskRunner" — `72`/`78`/`80`), external functions (`Engine.registerExternal`/`registerExternalAsync`, return→`Result.ok` / throw→`Result.error`, async via `Coop.blocking` — `41`/`71`), and keyword/function hiding (`Engine.setHiddenKeywords`/`setIgnoredFunctions` — `73`/`74`).
- **PE done — full conformance.** ✅ **All 84 `.tee/.expected` fixtures pass** byte-for-byte (`ConformanceTest`), deterministic, no flakiness over repeated runs; ~218 tests green. The seam-timing & async-replay-removal properties the design calls for were validated in the spike (`docs/spike-findings.md`).
- **PF done — 0.1.0.** ✅ Two-module Gradle build (`propertee-core` + `propertee-cli`) matching v1's layout; `propertee2` CLI (`cli/Main` — `-p` props, `--version`/`--help`); `dist` task → `dist/propertee2-0.1.0.jar` (fat jar, `java -jar`); version `0.1.0`; `README.md` + `CHANGELOG.md`. Real `SHELL`/HTTP/Task execution and the `StructuredTaskScope` swap (when it leaves preview) are post-1.0.

## Build/test

JDK 25 toolchain via Gradle (wrapper pinned to 9.3.1). The toolchain JDK is registered machine-locally in `~/.gradle/gradle.properties` (`org.gradle.java.installations.paths`); on a fresh machine add a JDK 25 home there. Two modules: engine in `propertee-core` (grammar at `propertee-core/grammar/ProperTee.g4`; antlr plugin generates the visitor into `com.flatide.propertee2.parser`), CLI in `propertee-cli`.

```bash
./gradlew build                 # compile + all tests, both modules (JDK 25, no preview flags)
./gradlew :propertee-core:test  # engine tests only
./gradlew test --tests 'com.flatide.propertee2.conformance.ConformanceTest'   # all 84 fixtures vs .expected
./gradlew :propertee-core:generateGrammarSource   # regenerate the ANTLR parser/visitor only

# CLI: dev run, or build the fat jar into dist/ and run it (JDK 25 at runtime).
./gradlew :propertee-cli:run --args="path/to/script.tee"
./gradlew dist && java -jar dist/propertee2-0.1.0.jar -p '{"width":100}' script.tee
```

> Fixtures are a **fixed baseline** copied from v1. `.expected` files are the correct answer **down to output order**, so never edit them (this is why the scheduler must be deterministic round-robin — design §6). The new engine conforms to the fixtures, not the other way around. The conformance fixture list is hardcoded in `conformance/Fixtures` (v1 convention); per-fixture semantics and host-injection caveats are in `docs/conformance-tests.md`.

## Conventions (value semantics — identical to v1, never change)

- **No null** — absence is `{}` (empty object). Missing arguments and no-return functions → `{}`.
- Numbers: integers are `Integer`, decimals are `Double`, **division is always Double**, and `.0` is stripped on formatting.
- Strict typing: `and`/`or` require boolean, arithmetic requires number. Exception: `+` coerces via `TO_STRING` (concatenation) if either side is a string.
- **1-based** indexing (`.1` is the first element). Integer object keys `obj.1` → string key `"1"`.
- Escapes `\" \\ \n \t \r` are handled in all string contexts; unrecognized escapes are preserved.
- Object-literal keys may only be quoted strings or integers.
- `deepCopy` (COW) on assignment and loop binding. `multi` workers see globals read-only (`::`, snapshot); writes are forbidden.
- Result: `{status, ok, value}` (running/done/error). Identical to v1 **down to the error message string**.
- The full spec and builtins are canonical in `docs/LANGUAGE.md`; the value contract is canonical in `docs/value-model-and-builtins.md`.
