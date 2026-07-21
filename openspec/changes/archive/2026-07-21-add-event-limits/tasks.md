# Tasks: Add Event Limits

## 1. Configuration and marker stamping

- [x] 1.1 Add the three event-limit constants to `backend/src/config.ts` — device capacity
  (default 10), event duration (default 30 days), grace period (default 1 day) — as source
  constants carried on `Config` (config-in-source law; tests inject shortened windows by
  constructing a `Config`); extend `backend/test/config.test.ts`.
- [x] 1.2 Add an `endsAt` helper (canonical cutoff form `yyyy-MM-dd'T'HH:mm:ss'Z'` arithmetic:
  `startsAt + duration`) in `backend/src/validators.ts` (`canonicalPlusSeconds`), with tests
  pinning the canonical shape (no milliseconds, `Z`, second precision).
- [x] 1.3 Stamp `endsAt` and `capacity` onto the marker in `POST /events` (`backend/src/app.ts`):
  extend `EventMarker`, resolve both from config at mint, include them in the `201` response;
  ignore any client-supplied `endsAt`/`capacity`. Update `app.test.ts` create-route cases
  (response fields, stamped marker body, client-supplied limits ignored, config change not
  reaching existing markers).

## 2. Lifecycle gate

- [x] 2.1 Implement the lifecycle classification as a pure function of the marker + now
  (live / grace / expired; missing `endsAt` or `capacity` ⇒ expired) next to `readMarker`, with
  direct unit tests on the boundaries (`now == endsAt`, `now == endsAt + grace`, legacy marker).
- [x] 2.2 Wire the check into the shared marker-read path so **every** event-scoped route
  (metadata GET, manifest PUT, files union GET, notify POST, leave DELETE) classifies the event
  before serving; expired ⇒ trigger the reap (task 3.x) and answer as absent (`404`). Non-404
  upstream marker-read failures stay `502`, never treated as a lifecycle state.
- [x] 2.3 `GET /events/:eventId`: return the marker including `endsAt`/`capacity` on `200`;
  remove the legacy read-time `startsAt` synthesis (superseded — a marker lacking `startsAt`
  lacks `endsAt` and is expired ⇒ reaped). Update the metadata-route tests: new fields present,
  legacy marker reaped-not-patched.

## 3. Expiry reap

- [x] 3.1 Extract/reuse the leave cascade's deletion machinery (manifest deletes,
  reference-checked GC of freed devices' bytes and config docs) into a whole-event reap that
  also deletes the marker **last**; unit-test the ordering (marker survives an interrupted
  cascade so the next touch completes it).
- [x] 3.2 Fan out the existing silent push (`apns.sendSilent` over `resolveMembership`) to
  active members **before** the deletes; a failed/partial fan-out must not abort the reap.
- [x] 3.3 End-to-end reap tests in `app.test.ts`: first touch past grace on each route class
  (metadata GET, manifest PUT) pushes then deletes then answers `404`; subsequent requests are
  indistinguishable from a never-created event; a legacy marker reaps identically.

## 4. Enrollment gate (capacity + grace)

- [x] 4.1 In the device-manifest `PUT` gate, list `events/<eventId>/devices/` and classify the
  writer as known (active or `.left` present) vs new; keep the token check first, then marker
  read, then the listing. Non-404 listing failures ⇒ `502`.
- [x] 4.2 Enforce the order: expired ⇒ reap + `404`; grace ∧ new device ⇒ `410`; live ∧ new
  device ∧ ever-enrolled count ≥ `capacity` ⇒ `409`; otherwise stream the write as today.
- [x] 4.3 Gate tests in `app.test.ts`: new device at capacity `409` (departed manifests count;
  leaving frees no slot), known device passes at capacity, rejoin passes, new device in grace
  `410` (also when simultaneously full — `410` wins), known device syncs through grace
  (manifest PUT, union GET, notify), byte route stays ungated and listing-free.

## 5. Verification and docs

- [x] 5.1 Run the backend suite (`cd backend && deno task test` and `deno task check`) plus
  `./gradlew build` (no client change — must pass untouched).
- [x] 5.2 Update `backend/README.md` if it documents the marker shape or event routes; confirm
  no client/spec surface outside the three delta'd capabilities changed (delta-completeness
  pass over the diff).
