# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`propertee2-java` is a **fully-cooperative runtime** for the [ProperTee](https://github.com/flatide/ProperTee) language. Its goal is to fundamentally resolve the eager seams left by [`propertee-java`](https://github.com/flatide/propertee-java) **v1** (Java 7/8, stepper-based) using **Java 21 virtual-thread (Project Loom) coroutines (Strategy B)**. **TeeBox now consumes this runtime** — it runs on propertee2 with no application-code changes, via a v1-compatible API surface layered on the cooperative engine (see Status).

> **Status: 0.12.0 — feature-complete, full conformance on spec v0.15.0; TeeBox composite-builds this repo's working tree** (its `settings.gradle` includeBuilds `../propertee2-java`, so TeeBox rides HEAD — its full suite was verified green against 0.11.0 on 2026-07-07, 187 tests; the old "TeeBox pins 0.2.x" note is obsolete). All design phases (spike → PA–PF) plus the TeeBox v1-API realignment (R1–R6) are done. The engine passes **all 113 `.tee/.expected` fixtures** (byte-for-byte, deterministic, no flakiness; **301 tests green**): JDK 25 Gradle build + ANTLR codegen, the `value/` model, the full builtin catalog (incl. host-gated ENV/file I/O, `SHELL`/`SHELL_CTX`, and `HTTP`/`HTTP_GET`/`HTTP_POST`), a recursive tree-walk interpreter (`interp/`), the **production Coop cooperative runtime** (`coop/`, `ScopedValue`) with the program as the root fiber + the `Coop.blocking` host contract, **`multi`/`thread`/`monitor`**, external-function registration, keyword/function hiding, an `Engine` embedding API, and a `propertee2` CLI.
>
> **TeeBox runs on this runtime unchanged.** A v1-API compatibility layer — the original `com.flatide.{task, runtime, platform, core, parser, interpreter, scheduler}` packages (the host `task` engine ported verbatim from v1; thin façades over `Engine`/Coop) — reproduces the host surface the frozen v1 exposed. So `SHELL` executes through a host-registered `TaskRunner`, the `ProperTeeInterpreter`/`Scheduler` façade streams `PRINT` live + emits main/worker thread-lifecycle events to a `SchedulerListener`, and props/globals/structured-result behave as v1. TeeBox's whole suite (incl. server integration tests) passes on v2; the only host-side change is a Java 25 toolchain. TeeBox shipped this as **1.0.0** (`flatide/TeeBox`), with the last v1-runtime release tagged `v0.12.0-propertee-v1`. It has since kept evolving **host-side only** (no runtime change) — e.g. **1.2.0** merges external `SHELL` task stdout/stderr into the client run-output endpoints (`taskLines`, default last 200 lines, `?taskLines=N`; `taskCount`/`tasks` breakdown), implemented entirely in the host `task`/server layer over the unchanged `Coop.blocking` SHELL task contract. **1.9.0** adds the run-result envelope (ProperTee `design-draft-result-handling.md` §5 host track): `GET /api/client/runs/{id}/result` carries an additive `result` field — the run as a ProperTee Result (`{status, ok, value}`, run = "thread #0") — plus `TeeBoxClient.getRunEnvelope`.
>
> Post-1.0: a standalone in-process `SimpleTaskRunner` and the `StructuredTaskScope` swap (when it leaves preview). (The `core`/`cli` module split landed in PF.) Spike writeup: `docs/spike-findings.md`; TeeBox-consumption realignment lives in the R1–R6 commits.
>
> **0.3.0 — spec v0.7.0 breaking batch shipped** (ProperTee issues #1/#2/#5/#6/#7; batching rationale in ProperTee `docs/design-notes.md`): strict boolean conditions, short-circuit `and`/`or`, single-arg `RANDOM` removed, `SLICE` count convention, strict `LEN`. The canonical spec (`flatide/ProperTee` LANGUAGE.md) and propertee-js shipped the same batch. **TeeBox note:** upgrading past 0.2.0 changes script semantics — review scripts against the migration notes in `docs/LANGUAGE.md` §Changelog before bumping the dependency.
>
> **0.4.0 — spec v0.8.0 first-class `null` shipped** (ProperTee issue #4): `null` is the seventh value type under a "**no implicit null**" principle — the language never produces it (missing args / bare `return` stay `{}`); it enters only via the `null` literal or data (`JSON_PARSE`, host values), making JSON round-trips lossless. Represented by the immutable `value/JsonNull.NULL` singleton; misuse (conditions/logic/arith/member access) fails loudly via the v0.7.0 strictness. Breaking: `null` reserved + `JSON_PARSE` stops normalizing `null`→`{}`.
>
> **0.5.0 — spec v0.9.0 `elseif` shipped** (ProperTee issue #3): Lua-style `elseif` — one chain, one `end`; first true arm wins, later conditions unevaluated (so untype-checked); strict-boolean per evaluated condition; hiding `if` covers the chain. Nearly non-breaking: `elseif` becomes reserved. **96 fixtures / 253 tests green, deterministic.**
>
> **0.6.0 — spec v0.10.0 Result escalation shipped** (no tracker issue — analyzed/adopted via ProperTee `docs/design-draft-result-handling.md`): `FAIL`/`UNWRAP`/`OK`/`ERR`/`IS_RESULT` built-ins + the **genuine-Result origin brand** (`value/TeeResult`, a `LinkedHashMap` subclass the `Result` factory returns; `Values.deepCopy` preserves it; invisible to TYPE_OF/display/JSON — only `UNWRAP`/`IS_RESULT` observe it). `FAIL`/`UNWRAP` are dispatched at the interpreter level (like PRINT/SLEEP) for line:col errors; catalog builtins stay positionless. Non-breaking. **101 fixtures / 263 tests green, deterministic.**
>
> **0.7.0 — spec v0.11.0 function-name resolution shipped** (no issue — surfaced by a v0.10.0 docs-accuracy review): resolution order pinned as host-blocked → script functions → built-ins/externals, i.e. script functions shadow everything. **Zero code change** — this runtime's `Interpreter.invokeNamed` order (ignored → script fns → PRINT/SLEEP/FAIL/UNWRAP → externals → catalog) already conformed; fixture `104_user_function_shadowing` pins it. v1/js switched from builtins-first. **102 fixtures / 268 tests green, deterministic.**
>
> **0.8.0 — spec v0.12.0 reserved all-uppercase namespace shipped** (no issue — ProperTee `docs/design-draft-reserved-uppercase-namespace.md`, grown from a rejected `sleep`-keyword proposal): `function NAME` matching `^[A-Z][A-Z0-9_]*$` is a definition-time runtime error (`Interpreter.defineFunction`, line:col) — ALL-CAPS is guaranteed built-in/host, script/built-in collisions structurally impossible (supersedes the v0.11.0 shadowing rule for built-ins). **Breaking**; fixture 104 retired, 75's helper renamed, 105/106 added; LANGUAGE.md gains §Reserved Function Names + §Blocking and Suspension. **103 fixtures / 270 tests green, deterministic.**
>
> **0.9.0 — static validation pass shipped** (ProperTee issue #9; host API, no language/spec change): `Engine.validate(source)` → `interp/Validator` scans the whole tree (dead branches included) and returns `"line L:C: 'X' is not available in this environment"` entries for hidden-keyword constructs / ignored-function calls. Runtime enforcement unchanged (backstop). Unit-tested in `ValidatorTest` (no conformance fixtures — it's a host API call, not script execution). **103 fixtures / 275 tests green.**
>
> **0.9.1 — v1-compat positioned error messages** (surfaced by the TeeBox envelope work): the v1 façade now converts `TeeError` → `com.flatide.runtime.ProperTeeError` (v1's class, ported) carrying `positioned()` (`"Runtime Error at line L:C: <msg>"`) as the exception message — v1 hosts read `e.getMessage()` as `errorMessage`, and the raw-`TeeError` rethrow was silently dropping the position (R-phase gap). Engine/CLI/conformance unchanged; `FacadeTest` pins it. **276 tests green.**
>
> **0.9.2 — v1-compat output channels** (surfaced by the 18_language_tour cross-runtime check): v1's engine is two-channel — `[THREAD ERROR]`/`[MONITOR ERROR]`/`iterationLimitBehavior="warn"` loop warnings go to the host's **stderr** sink — but this engine had a single `Sink`, so the façade fed those lines to the host's *stdout* sink (TeeBox mis-tagged them in run logs) and `"warn"` was silently ignored (a warnLoops run that completed under v1 failed under v2). Fix: `Interpreter.setErrorSink` (default = main sink → buffered `Engine.run` merges in program order, all 103 fixtures unchanged) + `setLoopLimitWarns`; the façade wires `builtins.stderr` and honors `"warn"`; the CLI streams live via a two-sink `Engine.run` overload (stdout/stderr split like the v1/js CLIs; `Runtime error:` stays stdout + exit 0). TeeBox unchanged. `FacadeTest` pins both. **278 tests green.**
>
> **0.10.0 — spec v0.13.0 "the edges pinned" shipped** (no issue — ProperTee `docs/design-draft-v1.0-gate.md`, the v1.0-readiness gate, tracked locally by user decision): probing the envelope the fixtures never covered found 3-way runtime divergence. **Integer envelope**: 32-bit signed pinned — out-of-range literals (`Integer literal out of range: …`, positioned; previously a raw `NumberFormatException` stack trace) and overflowing `+`/`-`/`*`/unary `-` (`Integer overflow`, positioned; previously silent wrap) are runtime errors, `FLOOR`/`CEIL`/`ROUND` (previously silent clamp) + `ABS(MIN)` raise the positionless catalog form; −2³¹ stays reachable as `-2147483647 - 1`; `JSON_PARSE`/`TO_NUMBER` still promote (data ≠ arithmetic). **Blocked-spawn containment** pinned by fixture 111 (this runtime already conformed; v1/js switched from setup-phase run failure). **Dispatch names reserved at the host boundary**: `Engine.requireReplaceableName` rejects `PRINT`/`SLEEP`/`FAIL`/`UNWRAP` in `Engine.register*`, `addRawBuiltin`/`addBlockingExternal`, and the façade `BuiltinFunctions.register*`. Breaking only in these corners. Fixtures 107–111; **108 fixtures / 291 tests green, deterministic.** Gate leftovers: double-display envelope (open, pre-1.0) + TeeBox burn-in → then v1.0.0 = content freeze.
>
> **0.11.0 — spec v0.14.0 "the number model & load-time rejection pinned" shipped** (no issue — ProperTee `docs/design-draft-v1.0-gate.md`, the last open gate item — item 4 + a revision of item 2). Closes the final pre-1.0 corner. **(1) Integer vs decimal is nominal (provenance-based)**: a number's kind is fixed where produced, not by whether its value is whole — `2.0 * 1500000000` is decimal `3000000000` (no overflow) while `2 * 1500000000` overflows; `JSON_PARSE`/`TO_NUMBER` of a `.`/`e`/`E` token is decimal even when whole. This runtime **already conformed** (nominal typing + lexical data entry) — no identity code changed; the reference is canonical (v1 re-boxed whole results, js was value-based/data-dependent). `SUM` over integers joins the overflow rule (`Builtins.SUM` widened accumulator). **(2) Display = ECMA-262 `Number::toString`** (`TeeFormat.formatDouble` rewritten): shortest round-trip digits, plain in `[1e-6, 1e21)`, exponential (`1e+21`, `1e-7`) outside — uniform across PRINT/TO_STRING/JSON_FORMAT; everyday bands (`0.0001`, epoch-ms, `6000000000`) stop leaking Java sci-notation. Digits from JDK 25 `Double.toString` (shortest for all realistic values) reformatted to ECMA; near-`MIN_VALUE` subnormals impl-defined. **(3) Blocked constructs rejected at load** (`Engine.run` pre-run `Validator.firstViolation` gate): a script naming any hidden keyword/ignored function anywhere — dead branches & `thread` spawns included — does not run at all (refused before the first statement, first violation in document order); same error string/position as the old runtime check (73/74 byte-identical), superseding v0.13.0 worker-containment. `Engine.validate` (#9) still lists all; v1 façade exposes no hiding so is unaffected. Fixtures 111 rewritten + 112–115 added; **112 fixtures / 299 tests green, deterministic; TeeBox 187 green.** Gate now: all items decided+shipped in reference → TeeBox burn-in → v1.0.0 = content freeze.
>
> **0.12.0 — spec v0.15.0 CONTAINS array membership shipped** (user feature request): `CONTAINS` now also accepts an array as its first argument and returns whether the second argument is an element — `CONTAINS([1,2,3], 2)` → `true`. Additive/non-breaking; membership uses `Values.valuesEqual` (the language's `==` — by value, deep for objects/arrays, strict across types), string-substring form unchanged, dispatched on arg0's type (`Builtins.CONTAINS`). Fixture 116; **113 fixtures / 301 tests green.** (js pending — playground; v1 out of scope — legacy. Note: a post-v0.14.0 feature, so the v1.0 content-freeze target is now the last v0.15.x.)

## Locked-in core decisions (agreed in a prior session — changing them requires justification)

1. **Two diverging lines** (revised 2026-07 by user decision). propertee-java (Java 7/8) serves the legacy expression-evaluator server and now receives **language-spec syncs** (semantics only — the 1.x line tracks the spec (1.5.0 = v0.12.0 + #9 validate), same fixtures byte-for-byte) plus security/bugfixes; its stepper architecture and host API stay untouched, and the `v1.0.0` tag remains the pre-sync baseline. All runtime features and full cooperativization happen in this repo (the modern runtime). Runtime architectures are **not synchronized**; language semantics are.
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
  grammar/ProperTee.g4                    #   grammar (identical to v1, unchanged). ANTLR4 visitor is
                                          #   generated into com.flatide.parser (the v1 package — R1)
  src/main/java/com/flatide/propertee2/   #   value/ coop/ host/ builtin/ interp/ + Parsing.java
  src/main/java/com/flatide/{core,interpreter,platform,runtime,scheduler,task}/
                                          #   v1-API compat layer (TeeBox host surface): ScriptParser,
                                          #   ProperTeeInterpreter/Scheduler façades, Task engine
  src/test/java + src/test/resources/tests/  #   unit + conformance tests; 103 .tee/.expected fixtures
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

## Main implementation — all phases done (PA–PF, then R1–R6)

- **PA done.** ✅ JDK 25 Gradle build (ANTLR visitor codegen, no preview flags), ✅ value model (`value/` — `TeeFormat` display+json, `Values`, `Result`, `TeeError`, `JsonParser`), ✅ parser facade + all-84-fixtures parse smoke test, ✅ full PURE builtin catalog (`builtin/Builtins` — math/type/string/string-matching/array+sort/object/JSON/timing) with return-type & error-message fidelity. HOST_GATED (`ENV`, file I/O) and BLOCKING (`SHELL`, `HTTP*`) builtins are deliberately deferred to PC/PD where they get the `Coop.blocking` contract.
- **PB done (sequential language).** ✅ Recursive tree-walk in `interp/` (`Engine`, `Interpreter`, `Ranges`, `Signals`, `UserFunction`) — instanceof-dispatch over the parse tree (no stepper/replay). Covers scopes (global/local/`::`), arithmetic+coercion, strict comparison/logic, 1-based access & dynamic keys, literals, ranges (BigDecimal float precision), control flow, functions, PRINT, pure builtins, escape processing, deepCopy/COW, and v1-exact errors (line:col). `ConformanceTest` runs the **46 sequential fixtures** byte-for-byte (the 38 multi/thread/SLEEP/monitor + host fixtures are the `PENDING` set, deferred to PC/PD).
- **PC done (core); host tail folds into PD.** ✅ Production Coop runtime in `coop/` (`Scheduler`/`Fiber`/`FiberState`/`Coop`) — ported from the hardened spike, deterministic round-robin, wake timer, `yield`/`sleep`/`blocking`, **`ScopedValue`** per-fiber context (§5). ✅ The interpreter runs as the **root fiber** (`Engine` → `coop.run`); `SLEEP` → `coop.sleep`; host-gated/blocking builtins route through `coop.blocking` (the §3.1 contract). ✅ `PlatformProvider` (`host/`) + `ENV` (`83`) + full file I/O (`85`, MKDIR/READ_LINES/WRITE_*/FILE_INFO/LIST_DIR/DELETE_FILE etc.) + HTTP.
- **PD done.** ✅ `multi`/`thread`/`monitor` driving the scheduler's `spawn`/`awaitChildren`/`wake`: isolated setup phase, read-only global snapshot, worker fibers with statement-boundary interleaving + purity (`::`-write errors), `[THREAD ERROR]`/`[MONITOR ERROR]` reporting, `{status,ok,value}` collection (auto `#N` + dynamic keys, dup detection), and a monitor that ticks mid-run + once final (matches v1's tick counts, robust to timing).
- **Host tail done.** ✅ `SHELL`/`SHELL_CTX` (core-only "requires a host-provided TaskRunner" — `72`/`78`/`80`), `HTTP`/`HTTP_GET`/`HTTP_POST` via host `PlatformProvider.httpRequest`, external functions (`Engine.registerExternal`/`registerExternalAsync`, return→`Result.ok` / throw→`Result.error`, async via `Coop.blocking` — `41`/`71`), and keyword/function hiding (`Engine.setHiddenKeywords`/`setIgnoredFunctions` — `73`/`74`).
- **PE done — full conformance.** ✅ **All 84 `.tee/.expected` fixtures pass** byte-for-byte (`ConformanceTest`), deterministic, no flakiness over repeated runs; ~218 tests green. The seam-timing & async-replay-removal properties the design calls for were validated in the spike (`docs/spike-findings.md`).
- **PF done — 0.1.0.** ✅ Two-module Gradle build (`propertee-core` + `propertee-cli`) matching v1's layout; `propertee2` CLI (`cli/Main` — `-p` props, `--version`/`--help`); `dist` task → `dist/propertee2-0.1.0.jar` (fat jar, `java -jar`); version `0.1.0`; `README.md` + `CHANGELOG.md`. A standalone in-process `SimpleTaskRunner` and the `StructuredTaskScope` swap (when it leaves preview) are post-1.0.
- **R1–R6 done — TeeBox realignment (0.2.0).** ✅ Parser codegen moved to `com.flatide.parser` (the v1 package — R1); the v1 `com.flatide.task` engine ported verbatim, with `SHELL` executing through a host-registered `TaskRunner` (R2); v1-name façades — `core.ScriptParser`, `platform.PlatformProvider`/`DefaultPlatformProvider`, `runtime.TypeChecker` (R3), `interpreter.BuiltinFunctions` + `interpreter.ProperTeeInterpreter` + `scheduler.Scheduler`/`SchedulerListener` (R4+R5); SHELL runId tagging + main/worker thread-lifecycle listener events (R6). Then: host-registerable BLOCKING builtins (off-baton, Result-wrapped) and the restored v1 `HTTP`/`HTTP_GET`/`HTTP_POST` builtins → released as **0.2.0**.

## Build/test

JDK 25 toolchain via Gradle (wrapper pinned to 9.3.1). The toolchain JDK is registered machine-locally in `~/.gradle/gradle.properties` (`org.gradle.java.installations.paths`); on a fresh machine add a JDK 25 home there. Two modules: engine in `propertee-core` (grammar at `propertee-core/grammar/ProperTee.g4`; antlr plugin generates the visitor into `com.flatide.parser` — the v1 package name, since R1), CLI in `propertee-cli`.

```bash
./gradlew build                 # compile + all tests, both modules (JDK 25, no preview flags)
./gradlew :propertee-core:test  # engine tests only
./gradlew test --tests 'com.flatide.propertee2.conformance.ConformanceTest'   # all 103 fixtures vs .expected
./gradlew :propertee-core:test --tests 'com.flatide.propertee2.interp.InterpreterTest'   # one test class
./gradlew :propertee-core:generateGrammarSource   # regenerate the ANTLR parser/visitor only

# CLI: dev run, or build the fat jar into dist/ and run it (JDK 25 at runtime).
./gradlew :propertee-cli:run --args="path/to/script.tee"
./gradlew dist && java -jar dist/propertee2-0.12.0.jar -p '{"width":100}' script.tee
```

> Fixtures are a **fixed baseline** copied from v1. `.expected` files are the correct answer **down to output order**, so never edit them (this is why the scheduler must be deterministic round-robin — design §6). The new engine conforms to the fixtures, not the other way around. The conformance fixture list is hardcoded in `conformance/Fixtures` (v1 convention); per-fixture semantics and host-injection caveats are in `docs/conformance-tests.md`.

## Conventions (value semantics — pinned to the spec; change only with a spec version bump)

> **Spec v0.7.0 breaking batch** (shipped in 0.3.0; ProperTee issues #1/#2/#5/#6/#7): `if`/`loop` conditions require a boolean (#1), `and`/`or` short-circuit left-to-right — right side unevaluated when the left decides (#2), single-argument `RANDOM` removed (#5), `SLICE`'s 3rd arg is a **count** like SUBSTRING/READ_LINES (#6), `LEN` on non-collections is an error (#7). Fixtures 87–92 cover the batch; 11/28/64 were updated.
>
> **Spec v0.8.0** (shipped in 0.4.0; issue #4): first-class `null` (`value/JsonNull.NULL`), fixtures 93–96; 84 updated.
>
> **Spec v0.9.0** (shipped in 0.5.0; issue #3): Lua-style `elseif`, fixtures 97–98.
>
> **Spec v0.10.0** (shipped in 0.6.0; no issue — ProperTee `docs/design-draft-result-handling.md`): Result escalation — `FAIL`/`UNWRAP`/`OK`/`ERR`/`IS_RESULT` + genuine-Result brand, fixtures 99–103. Non-breaking.
>
> **Spec v0.11.0** (shipped in 0.7.0; no issue): function-name resolution pinned — script functions shadow built-ins/externals; fixture 104. Zero code change here (this runtime already conformed); v1/js switched from builtins-first.
>
> **Spec v0.12.0** (shipped in 0.8.0; no issue — `design-draft-reserved-uppercase-namespace.md`): all-uppercase function definitions are a definition-time error — ALL-CAPS guaranteed built-in/host. Breaking; fixture 104 retired, 105/106 added. Everything below is otherwise identical to v1 (= spec v0.6.0).

- **No implicit null** — absence is `{}` (empty object): missing arguments and no-return functions → `{}`. `null` (spec v0.8.0) is pure data for lossless JSON round-trips — it enters only via the `null` literal or `JSON_PARSE`/host values; equality (`null == null` only) and `TYPE_OF` (→ `"null"`) work, everything else (conditions/logic/arith/member access) is a runtime error.
- Numbers: integers are `Integer`, decimals are `Double`, **division is always Double**, and `.0` is stripped on formatting.
- Strict typing: `and`/`or` require boolean, arithmetic requires number. Exception: `+` coerces via `TO_STRING` (concatenation) if either side is a string.
- **1-based** indexing (`.1` is the first element). Integer object keys `obj.1` → string key `"1"`.
- Escapes `\" \\ \n \t \r` are handled in all string contexts; unrecognized escapes are preserved.
- Object-literal keys may only be quoted strings or integers.
- `deepCopy` (COW) on assignment and loop binding. `multi` workers see globals read-only (`::`, snapshot); writes are forbidden.
- Result: `{status, ok, value}` (running/done/error). Identical to v1 **down to the error message string**.
- The full spec and builtins are canonical in `docs/LANGUAGE.md`; the value contract is canonical in `docs/value-model-and-builtins.md`.
