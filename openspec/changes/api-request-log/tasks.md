# Tasks

## 1. The request logger and its line

- [x] 1.1 Add the request-log module under `api/src/` (logger type with `field`/`error`, value encoding: bare `[A-Za-z0-9._:/-]+` or JSON-quoted, line formatter `<ISO> [<reqid>] <METHOD> <url> <status> <ms>ms v= in= out= <fields…>`). Its one console write carries `// deno-lint-ignore no-console`. Verify with unit tests in `api/test/` covering encoding (spaces, quotes, `=`, newlines), field order, repeated keys and `-` for an absent version or length
- [x] 1.2 Register it as the FIRST `app.use("*")` in `createApp`, typed through Hono `Variables` (`c.var.log`): mint the reqid, set the logger, `await next()`, wrap the body in a counting pass-through that writes the line on `flush`, or with `cut=true` (plus `err=` when it errored) on cancel or error; write at once for a body-less response. Verify with `api/test/` tests that capture the one write: one line per request, `out=` equals the bytes sent, `ms` includes a slow stream, `cut=true` on a cancelled body, and no IP or User-Agent on the line
- [x] 1.3 Count `in=` in the middleware by swapping the request for one with a counting body before routing; fall back to `Content-Length`, or `-`, when no route read the body. Verify with tests that a chunked upload without `Content-Length` logs its real byte count, and that an unread body logs its `Content-Length`
- [x] 1.4 Add `app.onError` that records `err="Name: message"` and answers 500 as today (Bugsink comes in group 4). Verify with a test whose route throws: one line, status 500, `err=`
- [x] 1.5 Remove `logged()` from `api/src/dev/serve.ts`, serve `handler` in both modes and correct the stale ephemeral-stdout comment. Verify that `deno task dev:local` prints one line per request from the middleware and that the `:adapter:generic:app:jvmTest` live-edge tests still pass

## 2. Routes record instead of printing

- [x] 2.0 Add `Refusal` (a Hono `HTTPException` subclass: status, text or JSON reply body, optional `err`, optional fields) and teach `app.onError` to answer it byte-identically and record its `err`/fields. Convert the support helpers (`upstream502`, `tryUpstream`, `orUpstream502`, `gateEvent`, `streamPut`, `noteAppVersion`/`declaredAppVersion`, the param/body readers, `closedRefusal`, `enrollRefusal`) to take values instead of `c` and throw `Refusal` instead of returning a Response; update every call site and re-throw `Refusal` in any broad handler `catch`. Verify that the full `deno task test` suite passes unchanged in status and body, and that `grep -n "c: Context" src/routes/support.ts` finds nothing
- [x] 2.1 Change `notifyMembers` (and the v1 notify path) to return `{ recipients, pushed, unsent, error? }` with no console calls, and record those as fields in every caller (byte upload, manifest, leave, v1 notify route). Verify that the existing notify/completion tests assert the returned outcome and the logged fields
- [x] 2.2 Convert every success and info console call in `routes/` into fields (`closed=true`, `served= trigger=`, `attested=`, `completed=`, `aborted=true`) and every refusal into `refused=` or `rejected=` (attest, renew, config, older manifest). Verify that the route tests assert the fields on the line for each of these
- [x] 2.3 Convert every caught server fault into `err="Name: message"` through `c.var.log.error`: `upstream502`/`tryUpstream`, bunny upstream statuses, the record/stamp/lookup/fetchability failures, the app-version record, the union-read record, the site upstream reads, the event page and health. Helpers return their failure instead of logging it. Verify with failing-upstream tests that each logs `err=` on its request's line
- [x] 2.4 Enable `no-console` for the edge script in `api/deno.json` (excluding `src/scripts/`, `src/dev/` and `src/lint/`). Verify that `cd api && deno lint` passes, that `grep -rn "console\." api/src --include=*.ts` outside those directories finds only the logger's single write, and that a deliberately added `console.log` in a route fails lint
- [x] 2.5 Document the line, its fields and the "only the handler touches `c`" rule in `docs/architecture.md` (api section). Verify that the doc's field list matches the inventory from 2.1–2.3

## 3. The DSN and the source map reach the bundle

- [x] 3.1 Pass `SENTRY_DSN` to the resolver step(s) of `deploy.yml`'s `api` job for the `prod` and `maintenance` bundles only, never `local`. Verify that a `deploy.yml` dry read shows the env on those steps only, and that `python3 scripts/resolve-deployment.py local` still renders an empty `sentryDsn`
- [x] 3.2 Make the bundle tasks emit `--sourcemap=external` and archive `api/dist/main.js.map` beside `main.js` in `bundle-<sha>`, keeping the rollback path working. Verify that `cd api && deno task bundle` produces both files, and that the rollback step's restore still finds `main.js`
- [x] 3.3 Document in `docs/deployment.md` that the api bundle carries the DSN on prod and maintenance and that `bundle-<sha>` holds the source map. Verify that the secrets/artifacts tables list both

## 4. Uncaught exceptions to Bugsink

- [x] 4.1 Add `@sentry/deno` to `api/deno.json` and call `Sentry.init` in `main.ts` only when `deployment.config.sentryDsn` is non-empty (`release = deployment.sha`, environment from the deployment, default integrations, no tracing, a `beforeSend` that drops `user.ip_address` and any client-IP header). Verify that `cd api && deno task bundle` succeeds and that a local run with an empty DSN never initialises Sentry
- [x] 4.2 Wrap each request in `Sentry.withIsolationScope` in the middleware and set the tags `reqid`, `route`, `v` and `platform=api`. In `app.onError` call `captureException`, record `bugsink=<event_id>`, await `Sentry.flush(2000)` and answer 500. Verify with an `api/test/` test that injects a stub ingest DSN: a throwing route yields one envelope with the tags, the request headers, no IP, and the line's `bugsink=` equal to the event id; two concurrent throwing requests each carry only their own fetch breadcrumb
- [x] 4.3 Teach `.claude/skills/bugsink/` to recognise an api event (`platform=api`), download `bundle-<release>`, and map `/mod.ts:L:C` frames through `main.js.map` to `api/src/...:line`. Verify by mapping a frame from a locally built bundle and its map to the expected source line
- [x] 4.4 Document backend failure reporting in `docs/architecture.md` (what is reported, the `bugsink=` field, headers kept, no IP) and the measured facts in this change's design. Verify that the doc points at the privacy-security requirement

## 5. After deploy

- [ ] 5.1 After the first deploy, send a burst of N known requests to production `/health` and confirm N lines in bunny's Edge Scripting log (checks for sampling or dropped lines); record the result in design.md's Risks
- [ ] 5.2 Confirm a real api event in Bugsink would be readable: trigger nothing in production; instead check that `bundle-<sha>` for the deployed sha contains `main.js.map` and that the skill maps a frame from it
