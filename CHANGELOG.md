# Changelog

## 0.34.0

**`:load` only loads, and `--result-only` prints a script's result** (host/CLI only, zero
language/spec change — the same release class as 0.31.0).

`:load` (user report: it ran the script at once, although `:run` and `:debug` exist for that):

- `:load <file>` reads the script, checks its syntax, and makes it the default target of
  `:run`/`:debug`/`:list`, printing `loaded <file> — :run runs it, :debug pauses at its first
  statement`. It no longer runs the script — no output, no globals or functions in the session —
  so the script can be listed and given breakpoints (`:b <line>`) before its first run. A syntax
  error is reported right away; the target still switches to the file, so after a fix in the
  editor `:run` alone picks it up.
- `:run`, `:debug`, and `:list` are unchanged, and `propertee2 -i script.tee` still runs the
  script before the first prompt (like `python -i`) and makes it the `:run` target.
- `:load` now matches only as a whole command (`:loadx` is no longer read as `:load x`), like
  `:run`/`:debug`/`:list`. `:help` describes the load/run split; the shared read-and-check step is
  `Repl.readAndParse`, and the run path is `Repl.runFile` (was `load`).

`--result-only` (user request: a script ending in `return true` printed nothing in script mode —
the top-level `return` is the host's result channel, kept apart from stdout since v1):

- `propertee2 --result-only script.tee` discards the script's `PRINT` output and writes only its
  result to stdout, as one line of JSON (`TeeFormat.json`, the `JSON_FORMAT` form). The result is
  the value TeeBox reports for a run: the top-level `return` value, else the final `result` global,
  else `{}`. Runtime diagnostics on stderr (`[THREAD ERROR]`, `[MONITOR ERROR]`, loop warnings)
  are kept. A runtime error leaves no result, so its `Runtime error:` line goes to stderr and the
  exit code is 1; the default mode keeps the conformance contract (error on stdout, exit 0).
- The option must precede the script path (later words are `_ARGS`); combining it with `-i`, or
  using it without a script, is a usage error (exit 2).
- Host API: `Engine.execute(...)` runs exactly like the live-sink `run(...)` overloads (which
  now delegate to it) and returns an `Engine.Outcome` — `hasExplicitReturn`, `returnValue`, the
  final globals, and `result()` with the resolution above. Script mode in `Main` now returns its
  exit code from `Main.run(...)` instead of calling `System.exit` inside, so tests can drive it.

**134 conformance fixtures / 471 tests green (both modules); dist rebuilt + smoke-tested. The
JavaScript runtime and TeeBox are unaffected (js has no `:load` or `--result-only`; TeeBox embeds
only propertee-core, whose change is additive).**

## 0.33.0

**Spec v0.25.0 — `TO_NUMBER` reads exactly a decimal numeral** (the `TO_NUMBER` divergence found
during 0.32.0: `TO_NUMBER("0x10")` was `16` in js but an error here, `TO_NUMBER("1f")` was `1` here
but an error in js). Mildly breaking, no grammar change; PURE catalog
(`Builtins.registerTypeAndString`):

- `toNumber` strips the six spec whitespace characters with the `trimWhitespace` helper `TRIM`
  uses, then matches the text against `DECIMAL_NUMERAL` — an optional sign, ASCII digits with an
  optional fraction (`.5` and `5.` included), an optional exponent — before any parser sees it.
  `Double.valueOf` used to read the type suffixes `f`/`F`/`d`/`D` (`"1f"` → `1`), hexadecimal
  floating point (`"0x1p3"` → `8`), `"NaN"`, and `"Infinity"`, and `Integer.valueOf` read
  non-ASCII digits (`"１２"` → `12`); `String.trim()` also dropped the other C0 controls. All of
  these are runtime errors now.
- Digits-only text that fits 32 bits is still an `Integer` and every other numeral a `Double`
  (spec v0.13.0/v0.14.0, as in `JsonParser.number`). A numeral too large for a double (`"1e999"`)
  is `TO_NUMBER() value out of range: '1e999'` instead of `Infinity`, which `JSON_FORMAT` wrote as
  the invalid JSON token `Infinity`.
- The conversion error is `TO_NUMBER() cannot convert 'x' to number` (was
  `Cannot convert 'x' to number`), quoting the argument as given; the js runtime uses the same
  text.

Fixtures 136 (`136_to_number_numerals` — numerals, then the rejected forms caught as worker error
Results) and 137 (`137_error_to_number_hex`), byte-identical with the JavaScript runtime. The
non-ASCII cases stay out of the ASCII `.expected` files and are covered by
`BuiltinsTest.toNumberNumerals` (and the same list in propertee-js `test/run_to_number_test.js`).
No new built-in, so TeeBox needs only the usual engine pickup and editor-asset refresh (inlined
bundle, `TO_NUMBER` reference entry).

**134 conformance fixtures / 460 tests green (both modules), 5× deterministic; dist rebuilt +
smoke-tested.**

## 0.32.0

**Spec v0.24.0 — `WORDS`, a pinned whitespace set, and no empty `SPLIT` delimiter** (user report:
`SPLIT("    test 1  2", " ")` returned `[ '', '', '', '', 'test', '1', '', '2' ]` where the words
`[ 'test', '1', '2' ]` were wanted). Mildly breaking, no grammar change; all three live in the
PURE catalog (`Builtins.registerTypeAndString`):

- `WORDS(s)` — new: the maximal runs of non-whitespace, in order, so never an empty string; empty
  or all-whitespace input → `[]`. `SPLIT` keeps its lossless contract (every field kept, so
  `JOIN(SPLIT(s, d), d) == s`).
- `TRIM` strips exactly the six spec whitespace characters (space, `\t`, `\n`, VT, `\f`, `\r`)
  through the `isWhitespace` helper that `WORDS` shares. `String.trim()` also stripped the other
  C0 controls (U+0000–U+0008, U+000E–U+001F), which the JavaScript runtime kept; `TRIM` now keeps
  them here too. `TO_NUMBER`'s surrounding-whitespace tolerance is untouched.
- `SPLIT(s, "")` is a runtime error (`SPLIT() requires a non-empty delimiter`, positionless
  catalog form). It used to return the characters plus a trailing `""` here but only the
  characters in js; `CHARS` is the way to split into characters.

Fixtures 134 (`134_words_whitespace`) and 135 (`135_error_split_empty_delimiter`), byte-identical
with the JavaScript runtime. Hosts pick `WORDS` up through `knownFunctionNames` (0.13.0), so
TeeBox's server lint needs no change; its static editor assets (inlined browser bundle,
highlighter list, reference panel) need the usual refresh.

**132 conformance fixtures / 455 tests green (both modules), 5× deterministic; dist rebuilt +
smoke-tested.**

## 0.31.0

**REPL re-execution commands** (host/CLI only, zero language/spec change — the same release class
as 0.18.0's REPL and 0.29.0; user request "REPL에서 로드된 스크립트를 다시 실행/디버깅").

- `:run [file]` re-executes a script in the session (defaulting to the last loaded/run one);
  `:debug [file]` restarts it paused before the first statement — a restart under the debugger
  without editing a `debug` statement in. `:list [file]` prints a script with 1-based line
  numbers, marking breakpoint lines with `*`, matching the numbers `:b` and the debugger use.
  An explicit path to `:run`/`:debug` becomes the new default target; `:list` only peeks.
- New engine hook `Engine.Session.eval(source, pauseAtEntry)` / `Interpreter.runSnippet(root,
  pauseAtEntry)`: a one-shot entry pause reusing the existing stepping machine (a `STEP` stop
  before the first root statement; ignored without a debug handler).
- Re-importing an alias already bound in a persistent session now re-resolves the same module id
  against its current stored content with fresh module globals (current-content semantics), so a
  re-run reflects edits to imported modules. A different module id under the same alias, or a new
  alias duplicating a still-bound identity, remains an error.

**130 conformance fixtures / 450 tests green (both modules), 5× deterministic; dist rebuilt +
smoke-tested. The JavaScript playground REPL and TeeBox are unaffected (js has no `:load`-style
REPL; TeeBox builds a fresh interpreter per run).**

## 0.30.0

**Spec v0.23.0 — depth-one imports and function-only modules.**

- Added `import util.file as file` / exact `import util.file.2 as file_v2` and qualified
  `file::function(...)` calls. Imported top-level code is skipped; only direct root functions are
  registered. Module globals are private, per-run, initially empty, and initialized explicitly.
- Added the host `ModuleResolver` contract. CLI resolution uses the entry directory (or
  `--module-root`), selects the greatest numeric `file.N.tee`, falls back to `file.tee` only when
  no numeric version exists, and rejects real paths outside the root.
- All direct imports resolve, parse, and validate before entry side effects. Nested imports,
  duplicate aliases, and duplicate resolved identities fail the load. Arguments and returns are
  deep-copied across the boundary.
- `multi` snapshots entry and module globals together. Imported functions run in workers against
  the module snapshot under existing purity rules. Debug stops, call sites, and module-specific
  breakpoint sets carry source identity; monitor execution remains excluded.
- The v1 compatibility façade exposes the module resolver and source-specific breakpoint sets for
  TeeBox. The Java 7/8 `propertee-java` implementation remains unchanged.
- Shared fixture 133 now pins latest/exact resolution, skipped module top-level code, state,
  deep-copy boundaries, and worker snapshots in both Java and JavaScript. Entry import-load errors
  retain the path-free compatibility format; qualified calls perform one return-boundary copy.

**130 conformance fixtures / 440 Java tests green; import behavior is covered by core and CLI
host-resolver tests.**

## 0.29.0

**Auto-display of function return values while stepping** (host API only, zero language/spec
change — the same release class as 0.13.0/0.26.0; user request "디버깅 중 함수의 반환값을 자동으로
표시할 수 있도록 API 추가").

- `DebugFrame.returns()` → `List<DebugReturn>` (`function` + deep-copied `value`): the
  user-function returns the step that produced this STEP stop crossed, chronological. Recorded
  are only normal returns on the stepping logical thread that land in the frame the step was
  armed in or shallower — the calls made from the stepped statement itself (stepOver/stepInto)
  or the function stepOut left, never their nested/recursive inner calls, so the list stays
  small and relevant (GDB `finish` / VS Code step-out semantics). Error unwinding records
  nothing; DEBUG_STATEMENT/BREAKPOINT stops deliver an empty list (the all-stop rule extends to
  crossed returns: a breakpoint hit drops them); `eval` from a break never records.
- CLI REPL `debug>`: a step stop first echoes `f returned <value>` per crossed return.
- DAP: the Locals scope leads with VS Code-style `(return) f` pseudo-variables right after a
  step; Globals stay plain. The VS Code extension needs no change.
- The v1 façade inherits the API automatically (`DebugFrame` is shared); a façade host like
  TeeBox reads `frame.returns()` in its handler with no new wiring.

**129 conformance fixtures / 425 tests green (both modules), 5× deterministic; dist rebuilt +
smoke-tested.**

## 0.28.0

**Spec v0.22.0 — debug inside `multi` workers; monitor remains excluded.** No grammar change and
no normal-run behavior change: without a debug handler, `debug` is still a no-op everywhere.

- Explicit `debug` statements and line breakpoints now stop in worker functions as well as the
  main logical thread. `DebugFrame.threadId()` / `threadName()` identify the paused worker while
  preserving its locals, read-only global snapshot, and call stack.
- `stepInto` / `stepOver` / `stepOut` are scoped to the logical thread that armed the step, so a
  ready sibling cannot steal the next step stop. An explicit `debug` or breakpoint may still stop
  any worker, as expected.
- Monitor iterations deliberately never stop or emit debug callbacks. A worker-side
  `DebugFrame.quit()` aborts the whole run promptly but only aborts the current Scheduler, so an
  `Engine.Session` remains reusable just like a main-thread debugger quit.
- DAP maps core main id 0 to thread 1 and a paused worker id N to DAP thread N+1; the REPL prompt
  also prints worker identity. The JavaScript scheduler exposes matching worker identity/reason
  metadata and worker-scoped stepping; its synchronous monitor execution remains uninstrumented.
- The v1 façade receives the same worker frames without API replacement. The frozen Java 7/8
  `propertee-java` runtime is intentionally unchanged.

**129 conformance fixtures / 416 Java tests green (both modules), 5× deterministic; 120
JavaScript fixtures + 4 host-API tests green; TeeBox suite green unchanged.**

## 0.27.0

**Spec v0.21.0 — monitor thread information and default one-line SHELL progress.** This is mildly
breaking only for scripts that read a thread Result's `value` inside a `monitor`; grammar and the
post-`multi` result collection are unchanged.

- Each monitor capture keeps genuine Result identity and `status`/`ok`, but projects the copied
  `value` to `{id, key, functionName, state, returnValue, shell}`. `returnValue` holds the ordinary
  worker value. The live collection is never mutated, so after `multi ... end` the ordinary value
  is assigned exactly as before.
- `shell` is one replace-on-next-call slot for the worker's current or latest SHELL, not a command
  history. It exposes portable state plus host-provided task id, PID, exit code, elapsed time,
  final Result, and a default one-line output tail. `output` is blank with `updated=false` when the
  bounded capture did not change. No `tail` grammar was added and no separate watcher thread is
  created; the already-blocking SHELL worker polls its host task with bounded waits.
- `SimpleTaskRunner` now evicts the oldest bytes after its per-stream 1 MiB cap and retains the
  newest suffix, instead of discarding all later output. Complete large stdout remains an explicit
  redirection/file responsibility.
- TaskRunner remains source/binary compatible. Existing TeeBox runners supply the richer metadata
  through their existing API; its full test suite passes unchanged. The JavaScript runtime mirrors
  the monitor projection (its standard SHELL remains a process-less stub), and browser/docs bundles
  were regenerated. The frozen Java 7/8 `propertee-java` runtime is intentionally unchanged.
- The observation seam only routes to the original TaskRunner-backed SHELL: re-registering the
  name (`Interpreter.addRawBuiltin`) retires the seam, so a replacement runs unobserved and the
  monitor's `shell` projection stays `{}` (host-registered external SHELLs never had the seam).

Fixture 132 pins the portable monitor contract. **129 conformance fixtures / 410 tests green
(both modules), 5× deterministic; 120 JavaScript fixtures + 3 host-API tests green; TeeBox suite
green unchanged.**

## 0.26.0

**v1 façade debug hooks** (host API only, zero language change — the same release class as
0.13.0's `knownFunctionNames`; the prerequisite for TeeBox's failed-run debug re-run). The
0.22.0/0.23.0 debugger existed only on the core `Interpreter` and `Engine.Session`; a façade
host (TeeBox drives `ProperTeeInterpreter` + `scheduler.Scheduler`) had no way to reach it,
because the core interpreter is a local created inside `execute()`.

- `ProperTeeInterpreter.setDebugHandler(DebugHandler)` — arms `debug` statements, line
  breakpoints, and stepping on the façade run, with engine semantics unchanged (root logical
  thread in NORMAL context only; the handler runs on the paused fiber holding the baton, so all
  `DebugFrame` access belongs inside `onBreak` — a host serving remote debug clients relays
  commands to it via a queue, the `cli/Dap` Query/Resume pattern).
- `ProperTeeInterpreter.debugBreakpoints()` — a live concurrent set of 1-based lines owned by
  the façade; the per-run core interpreter **adopts this very set** (new core setter
  `Interpreter.setDebugBreakpoints`), so pre-run seeding and mid-run mutations from any thread
  both take effect.
- `DebugFrame.quit()` unwinds `execute()` with the raw `interp.DebugQuit` — deliberately NOT
  converted to `ProperTeeError`/`ProperTeeAborted`, so a host can record an operator-ended
  debug run distinctly from a script failure or a cancel. The main logical thread still reaches
  a terminal listener state (`FacadeDebugTest` pins all of the above).

**128 fixtures / 405 tests green, 5× deterministic; TeeBox suite green unchanged.**

## 0.25.0

**Spec v0.20.0 — `_ARGS`: positional invocation arguments** (user design request "CLI 파라메터를
스크립트에서 참조"; additive, non-breaking — no grammar change, `_ARGS` joins `_PROPS` as the
second reserved global).

- `_ARGS` is a **string array** of the positional arguments: on the CLI, everything after the
  script path (`propertee2 deploy.tee prod eu-west 42` — option parsing stops at the file, like
  python; the old "only one input file" error is gone). 1-based, iterable, and **always
  present** — the empty array when there are none. Elements stay strings; number entry is
  explicit (`TO_NUMBER`), consistent with nominal identity (v0.14.0).
- Deliberately **not** inside `_PROPS`: the documented iterate/dump patterns
  (`JSON_FORMAT(_PROPS)`, `loop k in KEYS(_PROPS)`) stay pure, and args don't collide with `-p`
  keys. A caller-supplied property literally named `_ARGS` wins — the same precedence rule as
  `_PROPS` (`ArgsTest` pins both).
- Wiring: `Engine.setArgs(List<String>)` (chainable; `Session` inherits it — the `-i` REPL and
  `:load` share the invocation's args), CLI script mode and REPL preload, and the DAP server's
  launch `args` — the VS Code launch-config field that existed since 0.5.0 now actually works
  (extension 0.6.1 documents it).
- Injection lives next to `_PROPS` in the interpreter (`builtinProps`), so scope behavior is
  identical: bare at top level, `::_ARGS` in functions/`multi` setup, worker/monitor snapshot
  reads.

Fixtures 130 (harness-injected; the .expected generated end-to-end through the real CLI with
real trailing args) and 131 (empty default). **128 fixtures / 400 tests green, 5×
deterministic.** Ecosystem: spec v0.20.0 (EN + ko + grammar copies), js synced (visitor
`setArgs` + `pt.js` trailing args + fixtures byte-identical, 122 passed), VS Code extension
0.6.1 (launch `args` description + README), v1 out of scope.

## 0.24.0

**`propertee2 --dap` — a Debug Adapter Protocol server on stdio** (host/CLI only, zero language
change; the piece that turns the VS Code extension's F5 into a real debugger with breakpoints,
stepping, stack, variables, and REPL/hover evaluation — closing the "VSIX real breakpoints"
deferral from 0.22.0/0.23.0).

- `cli/Dap`: framed DAP messages (Content-Length; parsed/serialized with the engine's own
  `JsonParser`/`TeeFormat.json` — no dependencies). Implements initialize/launch (with `props` →
  `-p`-style injection and `noDebug` = plain run)/setBreakpoints (live into
  `Session.debugBreakpoints()`, mid-run updates included)/configurationDone (start when both it
  and launch have arrived, order-independent)/threads/stackTrace/scopes (Locals + Globals)/
  variables (display form, flat)/evaluate (paused scope, hover + REPL)/continue/next/stepIn/
  stepOut/disconnect. Output and runtime errors arrive as `output` events; a script runtime
  error keeps the exit-0 CLI contract.
- Threading: the script runs on a daemon thread whose `DebugHandler` blocks on a command queue;
  since the paused frame is fiber-confined (ScopedValue), every frame access is shipped to that
  thread as a query and answered via a future. `stopped` reasons: breakpoint (with "debug
  statement" description for `debug`), step.
- Deliberate limits, documented: one thread ("main"), flat variables, optimistic breakpoint
  verification (a line with no statement never fires), no `pause` request (disconnect ends the
  process). Worker/monitor debugging remains out of scope.
- `DapTest` drives full sessions over in-memory pipes — the exact VS Code wire: breakpoint hit →
  stack/variables/evaluate → continue; `debug` statement stop → step; `noDebug`; runtime-error
  contract. (The test pipe is hand-rolled: `java.io.Piped*Stream` kills the stream when the
  writing *thread* dies, and the server legitimately writes from two.)

VS Code extension 0.6.0 (ProperTee repo) switches to this server as a `DebugAdapterExecutable`
— see its README; it requires runtime 0.24.0+.

**126 fixtures / 390 tests green, 5× deterministic.**

## 0.23.0

**Debugger phase 2 — stepping, call stack, line breakpoints** (host/CLI only, zero language
change; completes the pdb feature set on 0.22.0's hook).

Engine:

- A pre-statement hook (`Interpreter.debugStep`, called from `exec` — a single null check when
  no handler is attached, so conformance behavior and cost are untouched) drives two new break
  reasons alongside the `debug` statement: **STEP** and **BREAKPOINT** (`DebugFrame.reason()`).
- `DebugFrame.stepInto()/stepOver()/stepOut()` — pdb's step/next/return, with call depth =
  the local-frame stack size; any break disarms the pending step (one pause per statement, and
  stepping onto a `debug` statement yields that break only). A step armed when a run ends does
  not leak into the next snippet (reset per run).
- `DebugFrame.callStack()` → `DebugCallSite(function, line, column)` list, outermost first —
  maintained per-fiber (in `ExecContext`) only while a handler is attached, push/pop balanced
  by a captured gate.
- `DebugFrame.statementText()` — the paused statement's source (first line + " …" for blocks),
  sliced from the ANTLR char stream, so hosts can show *what* is about to run.
- `Interpreter.debugBreakpoints()` / `Engine.Session.debugBreakpoints()` — a live mutable set
  of 1-based lines (per-snippet/loaded-file numbering) that break before executing; fires every
  time the line runs (loop iterations included). Only consulted on the root logical thread in
  NORMAL context.

CLI (`debug>` prompt): `:n`/`:next`, `:s`/`:step`, `:r`/`:return`, `:w`/`:where` (call chain +
current statement), `:b [line]` / `:bd [line]` — breakpoints are also settable at the `pt>`
prompt before a `:load`. Step and breakpoint stops show the statement about to run
(`at line 7:0: b = inner(a)`); the `debug`-statement banner is unchanged from 0.22.0.

Deferred still: worker/monitor debugging, and real breakpoints in the VS Code extension (needs
an adapter↔runtime protocol; the engine hook is now sufficient for it).

`DebugHandlerTest` +5 (step over/into/out with call-stack assertions, per-iteration breakpoint,
statement-text slicing, no step leak across runs) and `ReplTest` +4 piped walks.
**126 fixtures / 386 tests green, 5× deterministic.**

## 0.22.0

**pdb-style interactive debugger — `DebugHandler` host API + REPL `debug>` prompt, `:load`,
`-i`** (user request "REPL로 파이썬과 같은 디버깅"; host/CLI only, zero language change — the
spec already defines `debug` as "pauses in a debug mode, no-op in normal execution", so this is
another debug-mode host, the same release class as 0.18.0's REPL. 126 fixtures byte-identical;
fixture 70's no-op contract untouched).

Engine (host API):

- `Interpreter.setDebugHandler` / `Engine.Session.setDebugHandler` — with a handler attached,
  each `debug` statement calls `DebugHandler.onBreak(DebugFrame)` **on the paused fiber, holding
  the baton**: the whole run freezes (workers, monitor ticks, sleep wakes) until the callback
  returns — a deliberate, documented exception to the no-blocking-on-baton contract, because a
  frozen world is what a breakpoint means. Sleep deadlines are absolute, so a long pause wakes
  overdue sleepers back-to-back on resume, in deadline order.
- `DebugFrame`: `line()`/`column()`, `eval(source)` — parse + execute **in the paused scope**
  (the innermost local frame is read and written; `return` echoes without unwinding;
  `break`/`continue` are swallowed so they cannot leak into the paused script's enclosing loop;
  a nested `debug` is a no-op — the debugger does not re-enter itself), `locals()`, `globals()`,
  and `quit()` → throws `DebugQuit` (an `Error`, like `AbortError`, invisible to script-level
  catches; carries no Coop latch, so an `Engine.Session` survives with all pre-abort state).
- Scope: breaks fire only on the root logical thread in NORMAL context. `debug` in workers and
  the monitor watchdog stays a no-op (no stdin from a worker; deferred).
- Deliberately not `execSnippet`: debugger evaluation keeps its hands off the session's
  top-level-return state (`hasExplicitReturn`/`returnValue`).

CLI:

- The REPL (bare `propertee2`), `:load <file>` (new — run a script into the session, state
  accumulates), and `propertee2 -i script.tee` (new — `python -i`: run the script in a REPL
  session, then stay in the REPL with its state) all attach the debugger: `debug` opens a
  `debug>` prompt in the paused scope — bare input evaluates there with the same multi-line
  continuation and echo rules as the REPL; `:c` continues, `:q` aborts the run keeping the
  session, `:vars`/`:globals` dump state, EOF resumes the run (piped input never hangs).
- Plain script mode (`propertee2 script.tee`) keeps `debug` a no-op — the spec's "normal
  execution" contract.

`DebugHandlerTest` (frame eval/mutation, top-level scope, return/break containment, re-entry
guard, worker/monitor exemption, DebugQuit session survival, error surfacing) + 6 piped
`ReplTest` scenarios. **126 fixtures / 377 tests green, 5× deterministic.**

## 0.21.0

**Spec v0.19.0 — `multi ... limit K`: a concurrency cap on the multi block** (grew out of a
worker-pool usability analysis, user-approved; design arc in ProperTee
`docs/design-draft-multi-limit.md`). Nearly non-breaking: `limit` becomes a reserved word (the
`elseif` class — scripts using it as an identifier stop parsing; zero occurrences across the
fixture corpus).

An optional `limit K` clause between the result variable and `do` caps how many workers run
concurrently. The natural one-thread-per-job worker pool becomes safe and self-balancing —
previously N jobs on K threads meant a hand-written striping idiom that could not rebalance
load and put no bound on concurrent `SHELL`/`HTTP` operations:

```
multi result limit 3 do
    loop job in ::jobs do
        thread $(job.name): runJob(job)
    end
end
```

- The limit expression is evaluated once at multi entry, in textual order — before the global
  snapshot and the setup phase (`MultiLimitTest` pins a `::` write inside the limit expression
  being visible to workers). It must be a **positive integer** (nominal, spec v0.14.0 — `limit
  2.0` is an error); zero/negative/decimal/non-number → positioned
  `multi limit must be a positive integer`. No "0 = unlimited".
- Setup is unchanged: `thread` statements still capture arguments and pre-register `"running"`
  entries; only body start is deferred. A waiting worker is externally indistinguishable from a
  running one — no new Result status, no host-observer change (all workers are announced
  up front, as before).
- Admission is deterministic: the first K spawn at multi start; each finishing worker (success
  or `[THREAD ERROR]`) admits the next **in spawn order**, on-baton
  (`Interpreter.runThreads` gating; the coop scheduler is unchanged). `K >=` spawn count ≡ no
  limit; `limit 1` = sequential in spawn order.
- The monitor watchdog does not consume a slot and observes waiting workers as `"running"`.
- Abort (0.16.0) composes: a worker dying of `AbortError` still admits the next, which aborts at
  its own first checkpoint — the chain guarantees every announced worker reaches a terminal
  observer state and the parent is always released (`FacadeTest` pins it).

Grammar: `parallelStmt : K_MULTI resultVar=ID? (K_LIMIT limitExpr=expression)? K_DO block
monitorClause? K_END`. Fixtures 126–129 (cap + admission order + `K >= N`, `limit 1`, zero,
decimal-nominal); conformance is now **126 fixtures**, byte-for-byte, deterministic over 5
repeated runs; **364 tests green**. The v1 façade needs no change (the clause flows through the
shared parser); v1 (propertee-java) is out of scope as usual.

## 0.20.0

**Spec v0.18.0 — whole-file & JSON file I/O: `READ_FILE` / `READ_JSON_FILE` / `WRITE_JSON_FILE`**
(user feature request; additive, non-breaking — same release class as 0.17.0's FIND family).
Three builtins completing the File-I/O group's symmetry, all HOST_GATED (off-baton via
`Coop.blocking`) and Result-returning:

- `READ_FILE(path)` — the counterpart `WRITE_FILE` never had: the whole file as one string
  (UTF-8, content-exact — no BOM stripping). `ok` with the content, `error` on a read failure.
  Large files should keep using the windowed `READ_LINES`.
- `READ_JSON_FILE(path)` — read + JSON-parse in one call, replacing the
  `JSON_PARSE(JOIN(UNWRAP(READ_LINES(path)), "\n"))` idiom. `ok` with the parsed value (same
  conversion as `JSON_PARSE`, first-class `null` included); `error` with the I/O failure's or
  the parser's message — the script keeps running either way. A leading UTF-8 BOM is ignored
  (RFC 8259 permits it; Windows editors add one — the old idiom choked on it).
- `WRITE_JSON_FILE(path, value)` — `READ_JSON_FILE`'s counterpart: serialize exactly as
  `JSON_FORMAT` would and write it (overwrite) with one trailing newline (a POSIX text file).
  The round-trip through `READ_JSON_FILE` is lossless.

Backed by a new `PlatformProvider.readFile` with a **default implementation** composing
`readLines` (newlines normalized to `\n` — lossless for JSON), so existing custom providers
keep working unchanged; `DefaultPlatformProvider` overrides it with a real whole-file
`Files.readString`. Fixtures 124–125 (temp-dir-injected like 85) + `FileIoErrorTest` corners
(BOM, missing file, parse error). Conformance is now **122 fixtures**, byte-for-byte,
deterministic over 5 repeated runs; **349 tests green**. Hosts pick the names up automatically
through `knownFunctionNames` (0.13.0).

## 0.19.0

**SHELL works out of the box in the CLI — `SimpleTaskRunner` ported from v1** (user request;
host/CLI only — zero language/spec change, the 120 fixtures are byte-identical). The v2 CLI had
dropped what the v1 CLI always wired: an in-process task runner. `SHELL("...")` under
`propertee2` (script mode **and** the 0.18.0 REPL) failed with the embedding default
`SHELL() requires a host-provided TaskRunner in this environment`.

- **`task.SimpleTaskRunner`** (propertee-core; ported from propertee-java 1.7.0, same
  semantics — the `TaskRunner` javadoc already named it): in-memory, in-process execution via
  `/bin/sh -c` (Linux/macOS; on a platform without `/bin/sh` the launch failure surfaces as the
  task's error output). Stdout/stderr captured by daemon drainer threads, capped at 1 MB per
  stream; `releaseTask` frees state immediately (SHELL calls it after extracting the result);
  `shutdown` force-kills leftover processes. No persistence/process groups/archival — hosts that
  need those keep providing their own runner (TeeBox's ManagedTaskEngine is unaffected).
- **CLI wiring only**: script mode and the REPL create one runner per invocation
  (`Engine.setTaskRunner`) and shut it down on exit. The bare `Engine` default stays
  `UnsupportedTaskRunner` — the embedding contract and conformance fixtures 72/78/80 are
  unchanged.
- Conformance untouched: **120 fixtures byte-for-byte, deterministic over 5 repeated runs;
  344 tests green** (real-`/bin/sh` SHELL tests added to `ShellTest`, plus CLI/REPL coverage).

## 0.18.0

**Interactive REPL + `Engine.Session` snippet API** (user request; host/CLI only — zero
language/spec change, the 120 fixtures are byte-identical; same release class as 0.9.0/0.13.0/0.16.0).

- **CLI**: bare `propertee2` (no script file) now starts a REPL on stdin. Globals and user
  functions persist across inputs; each input runs with full script semantics (`SLEEP`,
  `multi`/`thread`, builtins) as its own root fiber on one reusable Coop. An input whose parse
  fails **at end-of-input** (unclosed `if`/`loop`/`function` block, dangling operator) opens a
  `...>` continuation until the buffer parses — a blank line abandons it and reports the error.
  The value of a trailing bare expression (or a top-level `return`) is echoed in `PRINT` display
  form; the empty object `{}` — the language's "absence" — is not echoed. Script runtime errors
  print the script-mode `Runtime error:` line and the session continues. `:help`, `:exit`/`:quit`,
  Ctrl-D. Prompts/banner appear only on a terminal, so piped stdin produces clean output
  (`echo 'FIND("abcd","c")' | propertee2` → `[ 3 ]`). `-p` props apply to the session.
- **Host API**: `Engine.session(props[, platform], out, err)` → `Session.eval(snippet)` returns
  the echo value (Java `null` = nothing to echo — safe because the ProperTee `null` is the
  `JsonNull.NULL` singleton), `Session.globals()` for host inspection/injection between snippets.
  Engine restrictions/externals/task runner apply; the load-time rejection gate (spec v0.14.0)
  runs per snippet. Backed by `Interpreter.runSnippet` (run-identical semantics + echo capture)
  and `Parsing.SyntaxException.atEof()` (structural incomplete-input signal, no message sniffing).
- Conformance untouched: **120 fixtures byte-for-byte, deterministic over 5 repeated runs; 339
  tests green** (`EngineSessionTest` + `ReplTest` added).

## 0.17.0

**Spec v0.17.0 — plain-text position search: `FIND` / `FIND_FIRST` / `FIND_LAST`** (user feature
request). Additive, non-breaking. Three literal (non-regex) string-search built-ins reporting
1-based positions, registered in the PURE catalog (`Builtins.registerStringMatching`):

- `FIND(s, sub)` — array of **all** start positions, ascending, overlapping occurrences
  included (`FIND("aaa", "aa")` → `[ 1, 2 ]`); no occurrence → `[]`.
- `FIND_FIRST(s, sub)` / `FIND_LAST(s, sub)` — first/last position, `0` when absent (`0` never
  collides with a valid position under 1-based indexing). Overlap counting keeps the trio
  consistent: they are always the first/last element of `FIND`, and `FIND_LAST` agrees with
  `String.lastIndexOf`.
- Both arguments must be strings; an empty search string is a runtime error
  (`FIND() requires a non-empty search string`, positionless catalog form).

Positions are produced by integer arithmetic, so they are nominal integers (spec v0.14.0).
Fixtures 122 (`122_find_positions`) and 123 (`123_error_find_empty`); conformance is now
**120 fixtures**, byte-for-byte, deterministic over 5 repeated runs; 325 tests green. Hosts pick
the new names up automatically through `knownFunctionNames` (0.13.0) — TeeBox's editor lint
whitelists them with no host change.

## 0.16.0

**Cooperative run abort + carrier-slice yield.** Host API only — zero language/spec change (the
118 fixtures are byte-identical, verified over 5 repeated runs); same release class as 0.9.0's
`validate` and 0.13.0's `knownFunctionNames`. Motivation: a host (TeeBox) needs to stop a runaway
run (`infinite` loops whose condition never turns) and to keep one CPU-bound run from starving
every other run's virtual threads — JDK virtual threads are not time-sliced, so a spinning fiber
otherwise pins a carrier thread indefinitely.

- **Abort API**: `ProperTeeInterpreter.abort()` / `scheduler.Scheduler.abort()` (the v1-API
  façade surface TeeBox uses), backed by `Coop.abort()`/`coop.Scheduler.abort()`. Thread-safe,
  callable from any thread, latched (an abort issued before `execute` starts still applies).
  The aborted `execute` throws **`runtime.ProperTeeAborted`** — deliberately NOT a
  `ProperTeeError`, so hosts can record CANCELLED rather than FAILED.
- **Cooperative checkpoints**: the abort applies at statement boundaries, at every loop
  iteration (`Interpreter.runBody` — covering empty-body loops, which never reach a statement
  yield), on sleep wake (sleeping fibers are force-woken so a long `SLEEP` cannot delay the
  abort), when a `Coop.blocking` host call returns, and when a `multi` parent's children die.
- **Abort is not a script error**: the signal is `coop.AbortError extends Error`, structurally
  invisible to the `[THREAD ERROR]` wrap, the monitor tick catches, and the external-function
  Result wrap (all catch `RuntimeException` only). Aborted workers still reach a terminal
  thread-observer state (a cancelled run must not leave "running" thread entries in a host's
  run view), the guaranteed final monitor tick is skipped, and worker/monitor fibers die
  quietly. Pinned by new `CoopRuntimeTest`/`FacadeTest` cases.
- **Carrier-slice yield**: the same checkpoint calls `Thread.yield()` every 1024th time
  (`-Dpropertee2.coop.slice=N`, read once at startup), unmounting a compute-bound fiber so
  other runs' virtual threads get carrier time. The baton never moves — per-run determinism
  and the round-robin interleaving are untouched.
- Known limit (inherent to the cooperative model): a host call that never returns delays the
  abort until it does. In practice only `SHELL` without a timeout can block indefinitely — a
  TeeBox-style host kills the run's tasks alongside the abort; the bundled HTTP builtins have
  30-second connect/read timeouts.

**118 fixtures / 320 tests green.**

## 0.15.0

**Namespace cleanup — everything lives under `com.flatide.propertee2` now.** Host-API breaking,
no language/spec change (the 118 fixtures are byte-identical). The v1-API compatibility layer had
kept v1's original package names directly under `com.flatide` (`com.flatide.{core, interpreter,
platform, runtime, scheduler, task}` + the ANTLR-generated `com.flatide.parser`) — deliberate in
R1 so TeeBox could migrate without code changes, but bad namespace hygiene once the migration was
done. All of them moved to `com.flatide.propertee2.*`; the Maven coordinates are unchanged
(`com.flatide:propertee-core`).

- Ecosystem decision (user): v1 (propertee-java) takes `com.flatide.propertee.*` (shipped as its
  1.7.0), this runtime takes `com.flatide.propertee2.*`, and TeeBox follows this runtime only
  (import-only change, TeeBox 1.15.0).
- Migration for hosts: replace `import com.flatide.X` with `import com.flatide.propertee2.X` for
  the seven compat packages — mechanical; class names, behavior, and the exception surface
  (`com.flatide.propertee2.runtime.ProperTeeError`) are otherwise identical.

**118 fixtures / 312 tests green; TeeBox 202 green after the import sweep.**

## 0.14.0

**Spec v0.16.0 — the monitor is a watchdog thread.** The `monitor` clause is re-specified as an
automatically spawned **watchdog thread**: `monitor 500` sleeps 500 ms *between* iterations
(fixed-delay — which this runtime's fiber loop already did), follows the same purity rules as
worker threads, and observes the run through **captured snapshots**. Analyzed and adopted in
ProperTee `docs/design-draft-monitor-watchdog.md`; mildly breaking (details there).

- **Iteration scope**: each iteration now runs like a function invocation in a fresh local frame —
  local assignment, loops, and function calls work in the body (previously *any* assignment was a
  runtime error, and a latent bug hid function locals/params from bodies called out of a monitor).
  Locals do not carry over between iterations (persistent locals were considered and rejected —
  no init idiom exists; see the draft).
- **Worker purity**: `::` writes raise the same error as in a worker; the dedicated
  "read-only monitor" error is retired. Bare names are locals; the undocumented bare-name
  fallthrough to globals is removed (globals are read via `::`, as documented).
- **Capture, not live view**: the result-collection variable binds a `Values.deepCopy` snapshot
  taken as the iteration starts — consistent across suspension, mutation-proof; the next
  iteration captures fresh (running→done stays visible across iterations, fixture 56
  byte-identical).
- **Unchanged**: sleep-first fixed-delay cadence, guaranteed final iteration, `[MONITOR ERROR]`
  reporting, no setup-locals. `Mode.MONITOR` special cases in `Interpreter.lookupVar`/`assign`
  deleted; `ExecContext.injected` removed.

**Fidelity fix (review round 2): the global snapshot is taken at multi ENTRY again.** The spec
says "at multi entry" and v1/js snapshot before the setup phase, but this runtime's rewrite had
silently moved the deep copy after setup — a setup-phase `::` write was visible to its
workers/watchdog. Restored per the locked v1-fidelity decision (`Interpreter.execMulti`); no
existing fixture covered the corner, fixture 121 now pins it.

Fixture 32 rewritten (global-write error), 117–121 added (iteration locals/loops/function calls;
capture under suspension; nested `::g.x` member-write guard with the global left untouched; fresh
scope per iteration; entry-time snapshot) → **118 fixtures / 312 tests green, deterministic.**
119–121 came out of two post-ship review rounds (which also caught js-side purity holes — see
ProperTee `docs/design-draft-monitor-watchdog.md` §Post-ship review findings); the same review
flagged the local `docs/LANGUAGE.md` copy as contradicting the shipped semantics, so it is now
content-synced through v0.14.0–v0.16.0 (nominal number identity, ECMA display, load-time
rejection, CONTAINS, monitor watchdog — it had silently lagged at v0.13.0).

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
