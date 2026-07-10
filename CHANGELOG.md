# Changelog

## 0.13.0

**Host API — enumerate the callable function names.** No language/spec change (same class of
release as 0.9.0's `validate`). Hosts that lint scripts before running them need the runtime's
name set: all-uppercase names are reserved for built-ins/host functions (spec v0.12.0), so an
ALL-CAPS call outside this set can never be a script function and is guaranteed to fail at call
time — a zero-false-positive typo check. First consumer: TeeBox 1.13.0's editor pre-check
("unknown function 'SHEL' (did you mean 'SHELL'?)").

- `Builtins.names()` — snapshot of the registered catalog names.
- Façade `BuiltinFunctions.knownFunctionNames()` — the interpreter-dispatched names + the full
  catalog (incl. host-gated/blocking — ENV, file I/O, HTTP, SHELL) + the host builtins registered
  on the instance. The set is wiring-independent (a bare catalog is enumerated).
- `Engine.INTERPRETER_DISPATCHED_NAMES` — extracted as the single source for
  `requireReplaceableName` and the façade (was an inline string chain).

Additive, non-breaking. **113 fixtures / 302 tests green** (+`FacadeTest.knownFunctionNames…`).

## 0.12.0

**Spec v0.15.0 — CONTAINS checks array membership.** Additive, non-breaking. `CONTAINS` now
accepts an array as its first argument and returns whether the second argument is an element —
`CONTAINS([1, 2, 3], 2)` → `true`. Membership uses the language's `==` (`Values.valuesEqual`), so
it is by value and deep for objects/arrays (`CONTAINS([{"a":1}], {"a":1})` → `true`) and strict
across types (`CONTAINS([1, 2], "2")` → `false`). The existing string-substring form is unchanged;
the built-in dispatches on the first argument's type (`Builtins.CONTAINS`). Fixture 116 →
**113 fixtures / 301 tests green**.

## 0.11.0

**Spec v0.14.0 — the number model & load-time rejection pinned** (ProperTee
`docs/design-draft-v1.0-gate.md`, the last open v1.0-gate item — item 4, plus a revision of item
2; tracked locally). The final corner the fixtures never covered. Breaking only in those corners —
all previously-covered behavior is byte-identical.

- **Integer vs decimal is nominal (provenance-based)**: a number's kind is fixed where it is
  produced, not by whether its value is whole. `2.0 * 1500000000` is the decimal `3000000000`
  (never overflows) while `2 * 1500000000` overflows; `JSON_PARSE`/`TO_NUMBER` of a `.`/`e`/`E`
  token is a decimal even when whole. This runtime **already conformed** (nominal typing, lexical
  data entry) — no identity code changed; the reference is the canonical model here. `SUM` over
  integers now joins the overflow rule (`Builtins.SUM`: was a silent `int` wrap, now a widened
  accumulator that raises `Integer overflow`).
- **Display is ECMA-262 `Number::toString`** (`TeeFormat.formatDouble` rewritten): shortest
  round-trip digits, plain form in `[1e-6, 1e21)`, exponential (`1e+21`, `1e-7`) outside — uniform
  across PRINT/TO_STRING/JSON_FORMAT. Everyday bands (`0.0001`, epoch-ms, `6000000000`) now render
  plain instead of leaking Java scientific notation (`1.0E-4`, `1.50000005E7`). Significant digits
  come from JDK 25 `Double.toString` (shortest for all normal + realistic values), reformatted to
  ECMA placement; the near-`Double.MIN_VALUE` subnormal zone is implementation-defined.
- **Blocked constructs are rejected at load** (`Engine.run` gains a pre-run `Validator.firstViolation`
  gate, wired only when restrictions are configured): a script naming any hidden keyword or ignored
  function — anywhere, dead branches and `thread` spawns included — does not run at all; refused
  before the first statement, on the first violation in document order. Same error string/position
  as the old runtime check (fixtures 73/74 byte-identical), so this replaces the v0.13.0
  worker-containment behavior. The `Engine.validate` host API (issue #9) still returns all
  violations. The v1 façade exposes no hiding, so it is unaffected.
- Fixtures: 111 rewritten (blocked `thread` spawn now rejects at load); 112 (dead-branch load
  reject), 113 (ECMA display bands), 114 (nominal identity), 115 (SUM overflow) added →
  **112 fixtures / 299 tests green**. TeeBox composite-build suite green (187) on the new display.

## 0.10.0

**Spec v0.13.0 — the edges pinned** (ProperTee `docs/design-draft-v1.0-gate.md`, the
v1.0-readiness gate; tracked locally, no tracker issues). Probing the envelope the fixtures never
covered found three-way cross-runtime divergence; now specified and enforced. Breaking only in
those corners — all previously-covered behavior is byte-identical.

- **Integer envelope (32-bit signed)**: out-of-range integer literals (`Integer literal out of
  range: …`, positioned — previously an unhandled `NumberFormatException` escaped with a raw
  stack trace) and overflowing `+`/`-`/`*`/unary `-` (`Integer overflow`, positioned — previously
  silent Java wrap-around) are runtime errors; `FLOOR`/`CEIL`/`ROUND` (previously silently
  clamped to MAX) and `ABS` of −2³¹ raise the same positionless catalog error. −2³¹ stays a
  legal value, reachable as `-2147483647 - 1`. Data conversion (`JSON_PARSE`/`TO_NUMBER`) still
  promotes beyond-range integrals to decimals. Integer member-access literals and the monitor
  interval follow the same literal rule.
- **Blocked-spawn containment**: this runtime already contained a blocked function's `thread`
  spawn in the worker; fixture 111 now pins it (v1/js change to match).
- **Interpreter-dispatched names reserved at the host boundary**:
  `Engine.registerExternal/Async/Pure`, `Interpreter.addRawBuiltin`/`addBlockingExternal`, and
  the v1 façade's `BuiltinFunctions.register/registerBlocking` all reject `PRINT`/`SLEEP`/
  `FAIL`/`UNWRAP` with an `IllegalArgumentException` at registration time.
- Fixtures 107–111 (limits happy path; arithmetic/literal/builtin overflow errors;
  thread-ignored containment) → **108 fixtures / 291 tests green**.

## 0.9.2

**v1-compat fidelity fixes (host output channels).** v1's engine is a two-channel contract — the
interpreter takes separate stdout/stderr print sinks, and exactly three kinds of lines go to
stderr: `[THREAD ERROR]` (v1 `Scheduler.java:428`), `[MONITOR ERROR]` (`:318,355`), and the
`iterationLimitBehavior="warn"` loop warning. The propertee2 engine had collapsed everything into
one sink, so the v1 façade delivered those lines to the host's *stdout* sink (TeeBox tags run-log
lines by stream — they were mis-tagged), and `"warn"` was silently ignored (a `warnLoops=true` run
that completed under v1 failed under v2). Same class of gap as 0.9.1's positioned messages.

- **Engine**: `Interpreter` gains an error channel (`setErrorSink`; defaults to the main sink) for
  `[THREAD ERROR]` / `[MONITOR ERROR]` / loop warnings, plus `setLoopLimitWarns(true)` — the v1
  `"warn"` behavior (print `Warning: Loop exceeded maximum iterations (N), stopping loop` on the
  error channel, stop that loop, continue the run).
- **Façade**: wires the host's stderr `PrintFunction` to the error channel and honors
  `iterationLimitBehavior="warn"` — the v1 contract restored; TeeBox needs no change.
- **CLI**: streams live through the new two-sink `Engine.run` overload — program output on stdout,
  diagnostics on stderr, matching the v1/js CLIs (`... 2>/dev/null` now agrees across all three
  runtimes). The runtime-error contract is unchanged: `Runtime error: ...` on stdout, exit 0.
- Conformance unchanged: the buffered `Engine.run` merges both channels in program order (all
  emission happens on the baton), so all 103 fixtures stay byte-identical. `FacadeTest` pins the
  stream routing and the warn behavior (278 tests).

## 0.9.1

**v1-compat fidelity fix (host-facing error messages).** In v1, the source position was baked into
the runtime exception's *message* (`createError` → `"Runtime Error at line L:C: <msg>"`), and hosts
like TeeBox store `e.getMessage()` as the run's `errorMessage`. The propertee2 engine keeps the
position separate (`value/TeeError.positioned()`), and the v1 façade rethrew the raw `TeeError` —
so hosts received the bare message, losing the position (an R-phase gap; surfaced by the TeeBox
run-result envelope work: a `FAIL(...)` run's envelope `value` had no `line:col`). The façade
(`com.flatide.interpreter.ProperTeeInterpreter.execute`) now converts at its boundary:
`TeeError` → `com.flatide.runtime.ProperTeeError` (v1's class, ported verbatim) carrying
`positioned()` as the message. Engine/CLI/conformance surfaces unchanged; `FacadeTest` pins the
contract.

## 0.9.0

Adds the **opt-in static validation pass for host restrictions** (ProperTee
[#9](https://github.com/flatide/ProperTee/issues/9)) — a host-API feature, no language change,
no spec version bump (like the #8 tooling batch).

- **`Engine.validate(source)`** scans the whole parse tree — dead branches included — and returns
  one `"line L:C: 'X' is not available in this environment"` entry per hidden-keyword construct /
  ignored-function call (empty = clean; `interp/Validator`). Sandboxing hosts can now reject a
  script before execution instead of discovering a forbidden construct when input steers into it.
- Default behavior unchanged: `run()` still enforces the same restrictions at runtime (backstop).
- Unit-tested (`ValidatorTest`, 5 tests: dead-branch detection, all six hideable keywords, elseif
  chain covered by hiding `if`, every call site reported, clean/no-restriction cases). Conformance
  fixtures deliberately unused — the pass is a host API call, not script execution.

All notable changes to `propertee2-java`. Value/type/scope/error semantics are pinned to the
ProperTee language spec (`flatide/ProperTee` LANGUAGE.md) — identical to the frozen
`propertee-java` v1.0.0 up to spec v0.6.0, with the deliberate spec v0.7.0 breaking batch from 0.3.0,
first-class `null` (spec v0.8.0) from 0.4.0, `elseif` (spec v0.9.0) from 0.5.0, Result
escalation (spec v0.10.0) from 0.6.0, pinned function-name resolution (spec v0.11.0) from 0.7.0,
and the reserved all-uppercase function namespace (spec v0.12.0) from 0.8.0.

## 0.8.0

Implements **spec v0.12.0 — the all-uppercase function namespace is reserved** (analyzed in
ProperTee `docs/design-draft-reserved-uppercase-namespace.md`, which grew out of a rejected
`sleep`-keyword proposal). **Breaking** for scripts that define all-uppercase functions.

- `function NAME` where NAME matches `^[A-Z][A-Z0-9_]*$` is now a definition-time runtime error
  (`Interpreter.defineFunction`, with the definition's line:col): ALL-CAPS is guaranteed to be
  built-in/host-provided, and script/built-in collisions become structurally impossible —
  superseding the v0.11.0 shadowing rule where built-ins are concerned.
- Fixtures: `104_user_function_shadowing` retired (premise gone), `75_range_step_eval_once`'s
  helper renamed (`STEP` → `step_fn`), `105_error_reserved_function_name` +
  `106_function_name_case` added → **103 fixtures**.
- Docs: LANGUAGE.md gains §Reserved Function Names and §Blocking and Suspension (the suspension
  model: SLEEP is the smallest primitive; waits compose on top of it; async externals get the
  same `Coop.blocking` contract).

## 0.7.0

Implements **spec v0.11.0 — function-name resolution pinned**: host-blocked check → script-defined
functions → built-ins/externals, so a script-defined function shadows any same-named built-in or
external. **No code change** — this runtime already resolved names in that order (ignored →
script functions → `PRINT`/`SLEEP`/`FAIL`/`UNWRAP` → host externals → catalog); the spec now
guarantees it, and the new fixture `104_user_function_shadowing` pins it (102 fixtures total).
The v1/js runtimes switched from builtins-first to script-first in the same spec batch.

## 0.6.0

Implements **spec v0.10.0 — Result escalation (`FAIL`/`UNWRAP`) and genuine Results** (analyzed in
ProperTee `docs/design-draft-result-handling.md`; adopted without a tracker issue by user decision).
**Non-breaking**: no grammar/keyword changes, no existing behavior changes, all previous fixtures
byte-identical. No host-API change.

- **Five new built-ins**: `FAIL(message)` raises a runtime error at the call site (the escalation
  primitive — inside a `multi` worker it fails only that thread); `UNWRAP(res[, msg])` unwraps an
  ok Result or escalates with `TO_STRING(res.value)`; `OK(v)`/`ERR(v)` construct genuine Results;
  `IS_RESULT(x)` observes the origin. `FAIL`/`UNWRAP` are dispatched at the interpreter level
  (like `PRINT`/`SLEEP`) so their errors carry the call site's line:col.
- **Genuine-Result origin brand** (`value/TeeResult`, a `LinkedHashMap` subclass): every Result the
  runtime creates — Result-returning built-ins, external-function wrapping, multi collection, and
  `OK`/`ERR` — is branded; `Values.deepCopy` preserves the brand; JSON never produces it. Invisible
  to `TYPE_OF`/display/JSON/equality — only `UNWRAP` and `IS_RESULT` observe it. (The HTTP
  envelope, previously a hand-built map, now goes through the `Result` factory.)
- Conformance: 5 new fixtures (99–103) → **101 fixtures, 263 tests green, deterministic**.
  `docs/LANGUAGE.md` synced to spec v0.10.0.

## 0.5.0

Implements **spec v0.9.0 — `elseif`** (ProperTee issue
[#3](https://github.com/flatide/ProperTee/issues/3)). Nearly non-breaking — `elseif` becomes a
reserved word (see the migration note in `docs/LANGUAGE.md` §Changelog); existing nested
`else if ... end` chains keep working. No host-API change.

- **Lua-style `elseif`**: one chain, one `end`. Conditions are checked top to bottom; the first
  `true` arm wins and later conditions are not evaluated (and so not type-checked). The strict
  boolean rule (spec v0.7.0) applies to each evaluated condition; hiding the `if` keyword covers
  the whole chain.
- Grammar: `K_ELSEIF` token + `(K_ELSEIF elseifConds+=expression K_THEN elseifBodies+=block)*`
  (synced with the canonical `flatide/ProperTee` grammar).
- Conformance: 2 new fixtures (97–98) → **96 fixtures, 253 tests green, deterministic**.
  `docs/LANGUAGE.md` synced to spec v0.9.0.

## 0.4.0

Implements **spec v0.8.0 — first-class `null`** (ProperTee issue
[#4](https://github.com/flatide/ProperTee/issues/4)), added so JSON round-trips losslessly.
**Breaking for scripts** — see the migration note in `docs/LANGUAGE.md` §Changelog (`null` is now a
reserved word; `JSON_PARSE` stops normalizing JSON `null` to `{}`). No host-API change.

- **`null` literal and value type.** The design principle is **no implicit null**: the language never
  produces `null` (missing arguments and bare `return` still yield `{}`); it enters only via the
  `null` literal or data (`JSON_PARSE`, host values). Represented by the immutable `JsonNull.NULL`
  singleton in `value/`.
- **Inert data semantics.** Equality works (`null == null` true, `null == {}` false),
  `TYPE_OF(null)` → `"null"`, displays/JSON-formats as unquoted `null`. Conditions, logic,
  arithmetic, and member access on `null` are runtime errors via the existing spec v0.7.0
  strictness — no null propagation.
- **Lossless JSON.** `JSON_PARSE` preserves JSON `null`; `JSON_FORMAT` emits `null`.
- Grammar: `K_NULL` token + `NullAtom` (synced with the canonical `flatide/ProperTee` grammar).
- Conformance: 4 new fixtures (93–96), 1 updated (84_json) → **94 fixtures, 249 tests green,
  deterministic**. `docs/LANGUAGE.md` synced to spec v0.8.0.

## 0.3.0

Implements the **spec v0.7.0 breaking-change batch** (ProperTee issues
[#1](https://github.com/flatide/ProperTee/issues/1)/[#2](https://github.com/flatide/ProperTee/issues/2)/[#5](https://github.com/flatide/ProperTee/issues/5)/[#6](https://github.com/flatide/ProperTee/issues/6)/[#7](https://github.com/flatide/ProperTee/issues/7)).
**Breaking for scripts** — see the migration note in `docs/LANGUAGE.md` §Changelog. Hosts (TeeBox)
should review scripts before upgrading; no host-API change.

- **Strict conditions** (#1): a non-boolean `if`/`loop` condition raises
  `Condition requires a boolean value. Got <type>` instead of being silently falsy.
- **Short-circuit `and`/`or`** (#2): left to right; the right operand is not evaluated (side effects
  included) when the left side decides. Operand type errors report only the evaluated operand.
- **`RANDOM(max)` removed** (#5): single-argument calls raise `RANDOM() requires zero or two
  arguments`; use `RANDOM(0, max - 1)` for the old meaning.
- **`SLICE(arr, start, count)`** (#6): the third argument is a count (was a 1-based inclusive end in
  practice), unified with `SUBSTRING`/`READ_LINES`. Migrate `SLICE(a, s, e)` → `SLICE(a, s, e - s + 1)`.
- **Strict `LEN`** (#7): non-collections raise `LEN() requires a string, array, or object argument`.
- Conformance: 6 new fixtures (87–92), 3 updated (11/28/64) → **90 fixtures, 241 tests green,
  deterministic**. `docs/LANGUAGE.md` synced to spec v0.7.0 (header now follows spec versioning).

## 0.2.0

Restores the v1 HTTP builtins that were missing from the propertee2 builtin catalog (HTTP calls were
dead in embedded hosts such as TeeBox). Minor bump — the host interface gains a method.

- **`HTTP_GET`, `HTTP_POST`, `HTTP` builtins.** Registered as `Kind.BLOCKING`, so the interpreter runs
  them off the baton via `Coop.blocking` (design §3.1). They return the v1 Result shape
  `{status, ok, value:{status, body, headers}}` — a non-2xx response is `ok=false` with the real
  status/body, a transport failure is `ok=false` with `status=0`. `HTTP_POST` serializes an object
  body to JSON; options accept the `headers`/`header` alias and a `timeout`.
- **Host `PlatformProvider` gains `httpRequest` (+ an `HttpResponse` record).** `DefaultPlatformProvider`
  implements it over `HttpURLConnection` (timeouts, redirects, error-stream read, disconnect in
  `finally`). This is a new method on the host interface — direct implementers must add it.
- **`PlatformAdapter` bridges the v1 `com.flatide.platform.PlatformProvider` HTTP** to the host seam, so
  a v1-API host (e.g. TeeBox's `TeeBoxPlatformProvider`) exposes HTTP with no application-code change.
- Covered by `FacadeHttpBuiltinTest` (real loopback server; GET/POST/404/generic/transport-failure/
  JSON-body/header-alias through the façade path TeeBox uses).

## 0.1.0

First release of the Java 25 virtual-thread cooperative runtime. **Passes the full v1 conformance
suite — all 84 `.tee/.expected` fixtures, byte-for-byte and deterministic.**

### Runtime
- Recursive tree-walk interpreter (no stepper / `AsyncPendingException` replay machine).
- Production **Coop** cooperative runtime: one virtual thread per logical thread, a single baton
  (only one fiber runs at a time → purity, lock-free, determinism), deterministic round-robin
  hand-off, wake timer, and per-fiber context via `ScopedValue`. **Zero preview API dependencies**
  (`StructuredTaskScope` is still preview on JDK 25 and is avoided; `multi` is hand-rolled).
- `Coop.blocking` contract: `SLEEP`, host I/O, and external functions release the baton
  ("release → block → re-acquire") so blocking never freezes the scheduler — and async statement
  replay is gone (leading side effects run exactly once).
- `multi` / `thread` / `monitor`: isolated setup phase, read-only global snapshot (worker purity),
  statement-boundary interleaving, `{status,ok,value}` result collection (auto `#N` + dynamic keys),
  live monitor ticks, and `[THREAD ERROR]` / `[MONITOR ERROR]` reporting.

### Language & builtins
- Full value model — `Integer`/`Double` (division always `Double`), `String`, `Boolean`,
  insertion-ordered objects, 1-based arrays, no null (`{}`), COW deep-copy at boundaries, v1-exact
  display vs JSON formatting, and v1-exact runtime-error messages with positions.
- Builtin catalog: math, type, string + string-matching/regex, array + sort, object, JSON
  (parse/format), timing, `ENV`, file I/O, and `SHELL`/`SHELL_CTX` (core-only without a host
  TaskRunner).

### Host integration
- Built-in properties (`-p` / `_PROPS`), `PlatformProvider` (`DefaultPlatformProvider` for env +
  filesystem), external functions (`registerExternal` / `registerExternalAsync` /  `registerPure`,
  args & return deep-copied), and keyword/function hiding (`setHiddenKeywords` /
  `setIgnoredFunctions`).

### Tooling
- JDK 25 Gradle build (ANTLR visitor codegen, no preview flags); `Engine` embedding API and a
  `propertee2` CLI (`-p` props, `--version`, `--help`).

### Not in 0.1.0 (post-1.0)
- Real `SHELL` / HTTP / Task execution (requires a host TaskRunner).
- `StructuredTaskScope`-based `multi` (swap in once STS leaves preview).
- A `core` / `cli` module split (currently a single artifact).
