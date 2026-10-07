# Design

## Context

See proposal.md for the motivation. Current state:

- `api/src/main.ts` serves `createApp(...).fetch` through `BunnySDK.net.http.serve`. Under Edge Scripting
  that binds `Bunny.v1.serve`, not `Deno.serve`. Nothing logs per request.
- About 33 `console.*` calls live in `api/src/routes/` (`shared.ts`, `support.ts`, `v1.ts`, `v2.ts`,
  `site.ts`, `attest.ts`). Some are success facts (`v2 notify: … N pushed`, `event … closed`), some are
  refusals (`attestation rejected`, `older version refused`), some are caught upstream or best-effort
  failures. `scripts/` and `dev/` are command-line tools that never ship in the edge script; they keep
  their console output.
- There is no `app.onError`, so an uncaught throw goes to Hono's default handler, which prints a bare
  `console.error` and answers 500.
- `api/src/dev/serve.ts` wraps the handler in `logged()` (one line per request), except in `--ephemeral`
  mode. Its comment claims the test JVM stops reading stdout after the readiness line. That is stale:
  `test/edge/.../LiveEdge.kt` drains stdout to EOF on a daemon thread.
- `scripts/resolve-deployment.py` already projects `sentryDsn` into `api/src/deployment.ts`, but the live
  bundle carries `"sentryDsn": ""` (checked in `bundle-c3eb09b…`), because the deploy job's resolver step
  never receives the `SENTRY_DSN` secret. `deployment.sha` is already in the bundle.
- `deploy.yml` archives `api/dist/main.js` as `bundle-<sha>` after every green deploy.

## Goals / Non-Goals

**Goals:**
- Exactly one log line per request, the same in every way the api runs, with nothing else written by the
  edge script.
- Uncaught exceptions reach the operator with enough context to fix them: the stack as source lines, the
  request, and the request's outbound calls.

**Non-Goals:**
- Tracing or performance monitoring (no spans, no sampling).
- Reporting caught failures to Bugsink. They are expected upstream noise and stay as `err=` in the log.
- Shipping the log anywhere other than bunny's own Edge Scripting log.
- Scrubbing ids from the log or from failure reports (spec: privacy-security, "The service reports its own
  failures to the operator").

## Decisions

### D1. One line, written by the first middleware when the response body finishes
The first `app.use("*")` in `createApp` mints a 6-hex `reqid`, puts a request logger on Hono's context
(`c.set("log", …)`, typed through `Variables`) and calls `next()`. It then wraps the response body in a
counting pass-through stream and writes the line from that stream's `flush`, or from `cancel`/error with
`cut=true`. So `ms` covers the whole transfer and `out=` is the bytes actually sent. A body-less response
writes at once.

Format: `<ISO> [<reqid>] <METHOD> <url> <status> <ms>ms v=<x-snapsync-app-version|-> in=<n|-> out=<n>
<fields…>`. The full URL is kept, query included (e.g. `?filename=`). The line never carries the caller's
IP or User-Agent. Field values matching `[A-Za-z0-9._:/-]+` are written bare and anything else is
`JSON.stringify`'d, so a line parses unambiguously. A repeated key is written again in order; none repeats
today, because `eventsCompletedBy` filters on one eventId, so an upload notifies at most once.

`in=` is counted by the middleware itself: it swaps the request for one whose body is a counting
pass-through, before any route reads it. So every route is covered, with no helper involved. A body that is
never read (for example, a request refused before the upload) falls back to `Content-Length`, or `-`.

*Alternatives:* a wrapper around `app.fetch` in `main.ts` and `serve.ts` only. Rejected, because the api's
tests and the ephemeral backend would not log, and there would be two entry points to keep in sync.
Writing the line when `next()` returns: rejected, because it would miss streaming time and mid-stream
failures.

### D2. Routes record; only the handler touches `c`; helpers take values and throw refusals
Handlers call `c.var.log.field(k, v)` and `c.var.log.error(msg)`. Both only buffer, and neither writes.
No helper takes `c`:
- **Inputs are passed as values.** Path params, the token's device id, the declared app version, the
  request body (stream or parsed JSON) and the URL are read by the handler and handed over.
- **A failure reply is thrown.** The six helpers that took `c` did so to build a failure reply
  (`c.text`/`c.json` with 400, 403, 404, 409, 410 or 502) and to `console.*` it. They now throw a
  `Refusal`, a subclass of Hono's `HTTPException`, carrying the status, the reply body (text or JSON,
  byte-identical to today's), an optional `err` (a server fault) and optional fields (`refused=`,
  `rejected=`, `aborted=true`). `app.onError` is the one place that turns a `Refusal` into the response
  and records its `err`/fields on the line. Any other throw is a real bug (D4). Handlers lose their
  `if (x instanceof Response) return x` checks.
- **Outcomes are returned.** `notifyMembers` returns `{ recipients, pushed, unsent, error? }` and the
  best-effort helpers return their failure. The handler records it.

This keeps `c` at the HTTP edge and makes every helper testable as a plain function. Care point: a
handler's own broad `try/catch` around a helper must re-throw a `Refusal` rather than swallow it. Each
such catch is checked when converted.

*Alternatives:* helpers keep `c` and record on it (rejected: the rule). Helpers return a refusal value
that every call site converts with `answer(c, r)` (rejected: about 55 call sites keep an `if`, and the
handlers stay as long as they are).

`error()` is folded into the line as `err="…"`, one per error, in order, so there is exactly one line per
request. The cost: a request that hangs or is killed at bunny's time limit writes nothing. That was
accepted, since immediate error lines would break "one line per request".

Every existing console call maps to a field. Success facts become `closed=true`, `served=<n>
trigger=<why>`, `recipients= pushed= unsent=`, `attested=<platform>/<env>`, `completed=<0|1>` and
`aborted=true`. 4xx refusals become `refused=not-attested|vanished|older-version` or
`rejected="<reason> (<detail>)"`. Server faults (5xx and best-effort failures) become
`err="Name: message"`. The inventory is the set of console calls in `routes/` at the time of apply.

### D3. No `console.*` in the edge script, enforced by lint
`deno lint`'s built-in `no-console` rule runs over the edge script's sources: `api/src` minus `scripts/`,
`dev/` and `lint/`. The middleware's single write is the one `// deno-lint-ignore no-console`. This rides
on the existing `deno lint` step in `ci.yml`'s `api-test`.

### D4. Uncaught exceptions → Bugsink through `@sentry/deno`
`main.ts` calls `Sentry.init` only when `deployment.config.sentryDsn` is non-empty, so local, ephemeral
and test runs never initialise it. Settings: `release = deployment.sha`, `environment` from the
deployment, default integrations, no tracing.

The middleware wraps each request in `Sentry.withIsolationScope` and sets the tags `reqid`, `route` (Hono's
route pattern), `v` and `platform=api`. `app.onError` calls `captureException`, records `bugsink=<event_id>`
as the line's only error field, awaits `Sentry.flush(2000)` and answers 500. The report goes to the apps'
project (1).

Headers are kept as the SDK sends them, by decision (see the spec delta). A `beforeSend` removes
`user.ip_address` and any client-IP header, defensively. None was observed in the probe, but the promise
not to send the address must not depend on bunny never adding one.

*Measured 2026-10-07* (scratch probe: the planned wiring on a 3-route Hono app, @sentry/deno 10.76.0,
`deno bundle`, run first locally against a stub ingest, then on a throwaway Edge Script that was deleted
afterwards; the four probe issues were resolved):
- a sync throw reached Bugsink and `flush` returned true; the whole 500 took 0.38 s;
- two concurrent requests that each fetched a URL and then threw each carried only their own fetch
  breadcrumb, so the isolation scope works under `Bunny.v1.serve`;
- a floating rejection was captured as `fatal`. Locally the SDK re-throws and Deno exits; on bunny the
  script kept serving (`/ok` 200 right after);
- the event carried every request header: `user-agent`, `cdn-requestcountrycode`, `cdn-requeststatecode`,
  `cdn-ja4`, `cdn-*` ids. It carried no IP and no `user`;
- frames were `/mod.ts:<line>` with function `?`, which is why D5 exists.

*Alternatives:* a hand-rolled envelope POST through `fetch`. It stays the fallback if the SDK ever stops
working on bunny, but it would lose the breadcrumbs and the global rejection handler. A separate Bugsink
project: rejected, to keep one inbox.

### D5. Source maps, archived, mapped offline
The bundle task becomes `deno bundle --sourcemap=external`, producing `dist/main.js.map`. `deploy.yml`
archives it beside `main.js` in `bundle-<sha>`. The `/bugsink` skill maps an api frame (`/mod.ts:L:C`)
through the map of the event's `release` (the sha). This is the same pattern as dSYMs and R8 mappings.
Nothing extra is uploaded to Bugsink.

### D6. The DSN reaches the api bundle
The deploy job's resolver step receives `SENTRY_DSN`, for the `prod` and `maintenance` deployments only.
The resolver's JSON projection already carries `sentryDsn`, and `local` never has it. The repo is public,
so the DSN stays uncommitted, as the resolver's own comment requires.

### D7. Dev's `logged()` goes
`serve.ts` serves `handler` in both modes, and its comment about ephemeral stdout is corrected (LiveEdge
drains it). `journeys (ios)` keeps its per-request evidence, because the api now writes those lines itself.

## Risks / Trade-offs

- [The SDK's global rejection handler re-throws after flush] → On bunny the script kept serving
  (measured). Locally Deno exits, which is Deno's default for an unhandled rejection anyway.
- [Bugsink quota and noise from one bug hit by every request] → Bugsink groups events into one issue, and
  project retention is capped at 1000 events. Only uncaught exceptions are reported.
- [`flush(2000)` delays a 500 by up to 2 s] → Only on the failure path. 0.38 s was measured end to end.
- [A hung or killed request leaves no line] → Accepted (D2). Bunny's own platform log still shows the
  kill.
- [Does bunny's log keep every line (volume caps, sampling, retention)?] → Unknown. Checked after the
  first deploy by comparing a burst of N known requests with N lines.
- [File names and ids outlive an event in bunny's log] → Accepted. The log is operator-only, and the
  existing lines already carried ids.
- [The SDK grows the bundle] → Measured at apply: `dist/main.js` went from 886 KB to 1.37 MB, with a
  3.95 MB source map that is archived and never published. That is well inside Edge Scripting's 10 MB
  script limit.

## Migration Plan

One deploy. The bundle carries the middleware, the SDK and the DSN together. Rollback is the existing
`bundle-<sha>` republish. Ordering: `SENTRY_DSN` already exists as a GitHub secret for the iOS and Android
builds, so it only needs adding to the api job's `env`. No Edge Script env change and no migration.
