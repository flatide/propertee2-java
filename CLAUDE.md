# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`propertee2-java` is a **fully-cooperative runtime** for the [ProperTee](https://github.com/flatide/ProperTee) language. Its goal is to fundamentally resolve the eager seams left by the frozen [`propertee-java`](https://github.com/flatide/propertee-java) **v1.0.0** (Java 7/8, stepper-based) using **Java 21 virtual-thread (Project Loom) coroutines (Strategy B)**. **TeeBox** will consume this runtime once it stabilizes.

> **Status: PC in progress (Coop runtime live; file I/O next).** The real engine now spans: JDK 25 Gradle build + ANTLR codegen, the `value/` model, the full PURE-builtin catalog, a recursive tree-walk interpreter (`interp/`), and the **production Coop cooperative runtime** (`coop/`, `ScopedValue`-based) with the interpreter running as the root fiber. 47 conformance fixtures pass (46 sequential + ENV). Remaining in PC: file I/O builtins; then **PD** (multi/thread/monitor) clears the `PENDING` set. The throwaway **spike** (`spike/`) that validated the cooperative model is documented in `docs/spike-findings.md`.

## Locked-in core decisions (agreed in a prior session — changing them requires justification)

1. **Two diverging lines.** v1.0.0 (Java 7/8) is **frozen** for the legacy expression-evaluator server (security/bugfixes only). All new features and full cooperativization happen in this repo (the modern runtime). The two codebases are **not synchronized** (one-way freeze).
2. **Baseline = Java 25 LTS, stable APIs only.** Uses virtual threads (final) + `ScopedValue` (JEP 506, final in 25). **`StructuredTaskScope` is still preview in 25 (JEP 505, 5th preview), so avoid it** — `multi` is hand-rolled with `newVirtualThreadPerTaskExecutor` + direct fork/join (encapsulated so it can be swapped locally once STS is finalized). → **zero preview dependencies.** (Before starting, empirically verify on a real JDK 25 whether STS is still preview by compiling.)
3. **Execution model = single-baton vthread coroutine.** One logical thread = one vthread, with a baton (semaphore / park-unpark) ensuring **only one runs at a time** → preserves the existing purity, lock-free property, and determinism. The call stack itself is the continuation.
4. **⚠️ Invariant — "no blocking while holding the baton."** Blocking while holding the baton halts the entire cooperative scheduler (a bigger risk than pinning). **Every potential blocking point (SLEEP / host I/O / external functions / spawn join) must honor the `Coop.*` primitive contract of "release baton → block → re-acquire."** Enforce this contract through the host-external registration API.
5. **Restore the interpreter as a recursive tree-walk.** Delete v1's stepper / `SchedulerCommand` / `AsyncPendingException` replay machine. But the **scheduler is repurposed, not deleted** (baton, state machine, wake timer, monitor tick, result collection are retained).
6. **Value semantics must not differ from v1 by a single byte.** The only thing that changes is **scheduling**. Using `Coop.blocking` to eliminate async statement-replay also fixes the "leading side-effects executed twice" correctness issue.
7. **Deterministic round-robin ordering must be pinned** — otherwise the copied-over `.expected` files (output-order sensitive) will flap.

## Repository structure (current)

```
grammar/ProperTee.g4                      # Grammar (identical to v1, unchanged). ANTLR4.
docs/
  java25-vthread-runtime-design-ko.md     # ★Canonical design doc — model / Coop contract / preview decision / phases PA-PF / spike §10.1
  value-model-and-builtins.md             # ★Value-level semantic contract the new engine must reproduce
  conformance-tests.md                    # ★List of v1 semantic-equivalence tests + host-injection / interleaving notes
  LANGUAGE.md                             # Canonical language spec (copied from v1)
src/test/resources/tests/                 # .tee / .expected conformance fixtures (84 pairs, copied from v1)
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

## Main implementation — PA done, PB next

- **PA done.** ✅ JDK 25 Gradle build (ANTLR visitor codegen, no preview flags), ✅ value model (`value/` — `TeeFormat` display+json, `Values`, `Result`, `TeeError`, `JsonParser`), ✅ parser facade + all-84-fixtures parse smoke test, ✅ full PURE builtin catalog (`builtin/Builtins` — math/type/string/string-matching/array+sort/object/JSON/timing) with return-type & error-message fidelity. HOST_GATED (`ENV`, file I/O) and BLOCKING (`SHELL`, `HTTP*`) builtins are deliberately deferred to PC/PD where they get the `Coop.blocking` contract.
- **PB done (sequential language).** ✅ Recursive tree-walk in `interp/` (`Engine`, `Interpreter`, `Ranges`, `Signals`, `UserFunction`) — instanceof-dispatch over the parse tree (no stepper/replay). Covers scopes (global/local/`::`), arithmetic+coercion, strict comparison/logic, 1-based access & dynamic keys, literals, ranges (BigDecimal float precision), control flow, functions, PRINT, pure builtins, escape processing, deepCopy/COW, and v1-exact errors (line:col). `ConformanceTest` runs the **46 sequential fixtures** byte-for-byte (the 38 multi/thread/SLEEP/monitor + host fixtures are the `PENDING` set, deferred to PC/PD).
- **PC mostly done.** ✅ Production Coop runtime in `coop/` (`Scheduler`/`Fiber`/`FiberState`/`Coop`) — ported from the hardened spike, deterministic round-robin, wake timer, `yield`/`sleep`/`blocking`, **`ScopedValue`** per-fiber context (§5). ✅ The interpreter runs as the **root fiber** (`Engine` → `coop.run`); `SLEEP` → `coop.sleep`; host-gated/blocking builtins route through `coop.blocking` (the §3.1 contract). ✅ `PlatformProvider` (`host/`) + `ENV` (`83`) + full file I/O (`85`, MKDIR/READ_LINES/WRITE_*/FILE_INFO/LIST_DIR/DELETE_FILE etc.). **Remaining (PC tail / fold into PD):** `SHELL`/HTTP (blocking), tasks, external-function registration (§3.1 `registerBlocking`), keyword/function hiding.
- **PD.** `multi`/monitor — vthread executor + hand-rolled fork/join (STS encapsulated for later swap).
- **PE.** Conformance — run each fixture, diff stdout vs `.expected` (hardcoded list in `conformance/Fixtures`, host injections per `docs/conformance-tests.md`), deterministic ordering + seam-timing + async-replay-removal tests.
- **PF.** Docs/release (from 0.1.0).

## Build/test

JDK 25 toolchain via Gradle (wrapper pinned to 9.3.1). The toolchain JDK is registered machine-locally in `~/.gradle/gradle.properties` (`org.gradle.java.installations.paths`); on a fresh machine add a JDK 25 home there. Source layout is standard (`src/main/java`, `src/test/java`); the grammar stays at `grammar/ProperTee.g4` and the antlr plugin generates the visitor into `com.flatide.propertee2.parser`.

```bash
./gradlew build                 # compile + jar + all tests (JDK 25, no preview flags)
./gradlew test                  # run the JUnit5 suite
./gradlew test --tests 'com.flatide.propertee2.conformance.*'   # parser smoke over all 84 fixtures
./gradlew test --tests '*TeeFormatTest'                          # one test class
./gradlew generateGrammarSource # regenerate the ANTLR parser/visitor only
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
