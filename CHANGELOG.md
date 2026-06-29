# Changelog

All notable changes to `propertee2-java`. Value/type/scope/error semantics track the frozen
`propertee-java` v1.0.0 exactly; this runtime changes only scheduling (cooperative suspension).

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
