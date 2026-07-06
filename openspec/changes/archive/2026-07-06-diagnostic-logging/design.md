## Context

The iOS app and upload extension run as **separate processes** with separate sandboxes. Each writes a Kermit log to its own `Documents/debug.log` via a (currently duplicated) `FileLogWriter`, pulled over USB with `pymobiledevice3 apps pull <bundle> Documents/debug.log`. Verified constraints that shape this design:

- Only a process's **own** `Documents/` is reachable via house-arrest AFC. The shared App Group container (`group.app.snapsync/`, home of `ledger.db`) is **not** pullable over usbmux — confirmed against the device; the only off-device route to it is a full `mobilebackup2` backup. So the two logs **stay separate, per-process files**; a single physical shared log is not a viable pull target.
- The file logger already writes **verbatim** — the live device pull shows full UUIDs, asset IDs, URLs, and dates unmasked. The only `<redacted>` tokens are Apple framework symbols inside native stack traces (not our data). There is **no application-level redaction to remove or add**.
- Kermit is the KMP logging facade in use; there is **no KMP rolling-file library** to adopt (Kermit/Napier lack it; logback/log4j are JVM-only). Rotation must be hand-rolled.

The gaps this change closes: several seams are unlogged (background-pump triggers, `onOpenUrl`/`onPushToken`/`provisionEvent`, every HTTP request); lines carry no indication of what triggered the work or how long it took; and full-library enumeration leaves no trace of what it considered.

## Goals / Non-Goals

**Goals:**
- Every platform invocation and app entry point logs enter/exit with its parameters, result, and duration, in one uniform convention.
- Every line traces back to the entry point that triggered it via an ambient `[<entryPoint>]` prefix.
- Every HTTP request logs one line: method, URL, status, duration, request size, response size.
- SyncEngine enumeration is accountable (a per-cycle `seen / new / already-uploaded` summary) without a line per skipped asset.
- Each per-process log file is size-bounded (10 MB roll) and torn-line-free (atomic append).
- The verbatim, un-redacted property is preserved and documented.

**Non-Goals:**
- Unifying the two files into one physical log (App Group is not pullable; out of scope).
- Any redaction/masking (explicitly not added).
- A configurable severity floor or a Debug tier — everything stays at Info (the file logger's purpose is capture-everything for a personal test tool).
- New unit tests for the wiring — verification is manual/on-device (test-equipment framing).
- Changing any product behavior, sync semantics, or upload outcomes.

## Decisions

### D1 — New leaf module `:domain:logging` hosts the shared context + helper

The seams that need the enter/exit helper span **two independent `commonMain` islands** (`:capability:upload` → `BackgroundUploadPump`/`UploadCycle`; `:capability:download` → `DownloadController`, which does *not* depend on `:domain:engine`) plus iOS wiring. No existing module reaches all of them, and `commonMain` consumers cannot be served by the iosMain duplication the project uses today for `FileLogWriter`.

Create `:domain:logging` — a pure leaf (commonMain + iosMain), depending only on Kermit — holding:
- `LogContext` (the ambient holder, commonMain),
- `logInvocation(...)` (the enter/exit helper, commonMain),
- the consolidated `FileLogWriter` and `PublicNSLogWriter` (iosMain), collapsing today's two duplicated copies into one parameterized by process name + Documents path.

Each consumer adds one `implementation(project(":domain:logging"))` line (~6 modules).

**Alternatives considered:** put it in `:domain:engine` — rejected, because `:capability:download` has no engine dependency and would gain a `download → engine` edge purely for logging (an architectural smell). Duplicate per-module — rejected, cannot span the two commonMain islands without fragmenting. This decision retires the existing code comment that justified duplicating `FileLogWriter` ("no shared module both leaf wiring modules already depend on") — the commonMain consumers now justify the shared module.

### D2 — Ambient context is a process-global holder, read by the writer, outermost-wins

The prefix must be readable by `FileLogWriter.log(...)`, a **plain synchronous call** with no coroutine context and no knowledge of which thread's work triggered it. That requirement eliminates the tidy-looking options:

- `@ThreadLocal` (Native): readable synchronously but **only on the same thread** — Ktor (Darwin queue) and SQLDelight hop threads, so downstream lines would silently lose the prefix exactly where it is wanted.
- Coroutine context element (MDC-style): the writer isn't `suspend` and has no handle to the context — unreadable by the writer.
- **Process-global `var`**: readable from any thread, and survives dispatcher/thread hops because it isn't per-thread. **Chosen.**

`logInvocation` sets `LogContext.current = name` only if unset (outermost entry point wins) and restores it on exit; the writer prefixes `[current]` when present. This yields the trace-back: `DownloadController.reconcile` logs `[onForeground]` when foreground drove it, `[onSilentPush]` when a push did.

### D3 — Uniform `logInvocation` shape

A `suspend inline` helper wrapping each seam: logs `→ <name>(<params>)` on entry, sets context, times the body with `kotlin.time.TimeSource.Monotonic`, logs `← <name> = <result> (<ms>ms)` on exit (and `✗ <name> threw <error> (<ms>ms)` on throw), restoring context. Params/result are provided as short-string lambdas by the call site (not blanket `toString()`), so the caller controls verbosity and avoids dumping large/expensive objects. Everything at Info.

### D4 — HTTP logging via a custom Ktor client plugin

The stock Ktor `Logging` plugin emits multi-line request/response blocks with no timing or sizes. Install a small custom client plugin (`createClientPlugin`/`HttpSend` interceptor) on the shared `HttpClient` (the Darwin client factory) that measures elapsed monotonic time and reads request/response `Content-Length`, emitting **one** Info line: `<METHOD> <url> → <status> (<ms>ms, req=<bytes>, resp=<bytes>)`. It logs through Kermit, so it inherits the ambient `[entryPoint]` prefix for free. Covers all seven Ktor call sites with no per-call-site edits. OS-driven photo-byte transfers are not Ktor and remain covered by the platform-seam logs.

### D5 — Process lifecycle banners

Each composition root writes a boot banner on start naming the process and build version (`=== app process start build=<ver> ===` / `=== extension process start build=<ver> ===`) and a teardown line where a clean shutdown exists. The banner names the process because a reader may concatenate the two files; per-line process tokens remain omitted (the file identifies the process).

### D6 — SyncEngine enumeration summary emitted by `UploadCycle`

Keep the existing engine work/started/completed/failed lines and the deliberately-silent per-asset `AlreadyUploaded` skip. Add one summary per discover phase, emitted by `UploadCycle` (which drives enumeration and already logs `discovered N`/`suppressed N`) by tallying the engine's `SyncDecision`s: `enumeration: <seen> seen, <new> new, <already> already-uploaded`.

### D7 — Rotation: 10 MB roll to `.1`

On writer open (or before append when size is cheap to check), if `debug.log` exceeds 10 MB, rename it to `debug.log.1` (replacing any previous `.1`) and start fresh. Keeps one previous file; at any pull ~20 MB / ~10–14k photos of recent history is retained. Each line is written as a single atomic append (`O_APPEND`, one `write()` per line) so concurrent-thread writes within a process never tear a line.

## Risks / Trade-offs

- **Overlapping entry points mislabel a line** → iOS delivers app entry points serially per process, and this is a dev log, not an audit trail. Accepted, not engineered around.
- **Ambient context is near-constant (`[process]`) in the extension** → the extension's value axis is the cycle phase / asset key, already in its messages. The helper stays uniform; its payoff is app-side. Accepted (argues against inventing extension-specific machinery).
- **`:domain:logging` touches ~6 build files** → one `implementation` line each; the honest, idiomatic cost of a cross-cutting concern.
- **Params/result lambdas could still dump something large** → mitigated by making rendering explicit per call site (short strings), not a blanket `toString()`.
- **Two files still require a manual merge for a cross-process timeline** → acceptable per the two-file decision; both remain pullable and each line is timestamp-prefixed for ad-hoc `sort` merges if ever needed.
- **Rotation loses the older half on roll** → one `.1` backup retained; 10 MB chosen so a full backup session fits before rolling.

## Migration Plan

No runtime/data migration. `FileLogWriter`/`PublicNSLogWriter` move into `:domain:logging`; the two composition roots install the consolidated writers (same per-process `Documents/debug.log` paths as today), so the existing `apps pull` commands are unchanged. Ad-hoc log lines in instrumented seams are migrated onto `logInvocation` as they are touched. Rollback is deleting the module + reverting the writer install (no persisted state depends on it).

## Open Questions

- Exact byte thresholds for "short" param/result rendering conventions (left to implementation; no spec impact).
- Whether the app-driven upload path's several re-entry triggers (`onForeground`/`onBackgroundTask`/`onSessionEvents`/`onUploadCompleted`) each read cleanly as the outermost context in practice — to confirm on-device during manual verification.
