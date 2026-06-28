# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`propertee2-java` is a **fully-cooperative runtime** for the [ProperTee](https://github.com/flatide/ProperTee) language. Its goal is to fundamentally resolve the eager seams left by the frozen [`propertee-java`](https://github.com/flatide/propertee-java) **v1.0.0** (Java 7/8, stepper-based) using **Java 21 virtual-thread (Project Loom) coroutines (Strategy B)**. **TeeBox** will consume this runtime once it stabilizes.

> **Status: spike passed, pre-engine.** The repo holds the **assets needed to guarantee semantic equivalence with v1** (grammar, spec, fixtures, contract docs) plus a throwaway **spike** (`spike/`) that validated the cooperative model. The next task is the main implementation, starting at **PA** (design §10.1). See `docs/spike-findings.md`.

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

## Next task — main implementation (PA onward)

- **PA.** JDK 25 Gradle skeleton (toolchain 25, no preview flags) + port grammar/builtins/value-model + import the `.tee/.expected` fixtures.
- **PB.** Recursive interpreter (stepper removed).
- **PC.** Coop runtime (baton, `sleep`, `yield`, `blocking`) + host-external `Coop.blocking` contract (§3.1); swap `ThreadLocal` → `ScopedValue`.
- **PD.** `multi`/monitor — vthread executor + hand-rolled fork/join (STS encapsulated for later swap).
- **PE.** Conformance (all `.expected` pass, deterministic ordering) + seam-timing tests + async-replay-removal test.
- **PF.** Docs/release (from 0.1.0).

## Build/test (update once scaffolded)

**No Gradle or test runner yet.** Build/lint/test commands will be filled in here when the **JDK 25 toolchain** (no preview flags) Gradle skeleton is created in PA. Until then, conformance fixtures can only be run against the v1 runtime.

Commands usable at the current (pre-implementation) stage:

```bash
# Check fixture-pair integrity (every .tee must have a .expected — currently 84 pairs)
cd src/test/resources/tests && for f in *.tee; do [ -f "${f%.tee}.expected" ] || echo "MISSING: ${f%.tee}.expected"; done

# Find fixtures for a specific scenario (e.g. multi/monitor/async)
ls src/test/resources/tests/ | grep -E 'multi|monitor|thread|async'

# Inspect a pair (input .tee ↔ expected output .expected)
cat src/test/resources/tests/49_multi_result_collection.tee
cat src/test/resources/tests/49_multi_result_collection.expected
```

> Fixtures are a **fixed baseline** copied from v1. `.expected` files are the correct answer **down to output order**, so never edit them (this is why the scheduler must be deterministic round-robin — design §6). The new engine conforms to the fixtures, not the other way around. For per-fixture semantics and host-injection caveats, see `docs/conformance-tests.md`.

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
