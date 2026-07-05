# Changelog

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
