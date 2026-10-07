# Proposal

## Why

The deployed api writes no line per request. Only a scattered handful of `console.*` calls on notable
events and errors reach bunny's Edge Scripting log, so the operator cannot see what a device or a browser
actually asked for, what it was answered, or how long it took. An uncaught exception reaches nobody: Hono's
default handler prints a bare stack into that same log, and nothing alerts the operator. The local dev
server already logs one line per request, which is why the gap shows only in production.

## What Changes

- Every request the api serves writes **exactly one** log line when its response body finishes:
  timestamp, a per-request id, method, full URL (query included), status, duration, the app version the
  caller declared, request and response byte counts, and the fields the route added. This holds in
  production, local dev, the ephemeral test backend and the api's own tests.
- Routes stop writing to the console. A handler records what it did (push counts, a close, a refusal, a
  caught failure) as `key=value` fields on that one line; helpers return their outcome to the handler
  instead of logging. Client refusals (4xx) are fields; server faults are `err=` fields. A body that fails
  or is abandoned mid-stream is marked `cut=true`. A lint rule keeps `console.*` out of the edge script.
- Uncaught exceptions are reported to the operator's error tracker (the Bugsink instance the apps already
  report to) with the stack, the request (URL and headers as received) and the outbound calls the request
  made. The log line carries only the report's id. Production bundles carry the reporting key; local and
  test runs report nothing.
- Each deployed bundle is archived together with a source map, so the operator can read a reported stack
  as source lines.
- The privacy promise to web visitors is narrowed: a failure while serving a visitor sends that request's
  details, including the visitor's browser, approximate location and connection fingerprint, to the
  operator's error tracker. The visitor's network address is still never recorded.
- The local dev server's own request logger is removed, because the api now logs for itself.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `privacy-security`: adds what the service sends the operator when it fails while handling a request.
  Amends "A web visitor leaves no trace in the event" so that such a failure report may carry the
  visitor's browser, approximate location and connection fingerprint, but never their address.

## Impact

- `api/src/`: a new request-log middleware and error handler in `app.ts`; every route module under
  `routes/` (the console calls become fields; `notifyMembers` and the other helpers return outcomes); a
  Sentry init in `main.ts`; `deno.json` (the `@sentry/deno` dependency, `no-console` in lint, a source map
  in the bundle task).
- `api/src/dev/serve.ts`: `logged()` is removed and its stale comment about ephemeral stdout is corrected.
- `scripts/resolve-deployment.py` and `.github/workflows/deploy.yml`: the DSN reaches the api's prod and
  maintenance bundles, and `bundle-<sha>` also archives `main.js.map`.
- `.claude/skills/bugsink/`: it learns to map an api frame through the archived source map.
- `docs/architecture.md` and `docs/deployment.md`: the request log line, its fields and the backend's
  failure reporting.
- New dependency: `@sentry/deno` (measured working on Bunny Edge Scripting, 2026-10-07; see design.md).
