# Design

## Context

- **The request log** (`api/src/request-log.ts`, `request-log-store.ts`, migration 0012; `docs/architecture.md`,
  "The request log") writes exactly one record per request when its body finishes: a console line and a
  `request_log` row with fixed columns (`at, reqid, method, url, status, ms, version, bytes_in, bytes_out`),
  a `fields` JSON object and `errors`. Rows are kept 30 days (`REQUEST_LOG_RETENTION_DAYS`) and read with
  `deno task logs`. A request the platform kills at its time limit leaves no record (that change's decision).
- **Diagnostics kept in operational tables today**: `union_log` `fetch` rows (the read record: device, trigger,
  cursor, served — written best-effort, owning the `read-log` fault, read by no code) and its `removed` rows
  (read by no code); `union_log.at` on every row (read by no code); `devices.app_version` (read only by the
  sweep's version table), `devices.attested_at` and `devices.push_updated_at` (read by no code). The `gained`
  rows' `seq` is operational: the union cursor and the done-member check read it.
- **What the server cannot see**: a photo's capture, discovery and queueing on the uploader; the push's
  arrival, the download's end and the library import on the receiver. The download route is a `302` to a
  presigned storage URL, so its record marks the download's *start* and the bytes never pass the edge.
- **iOS transfers run in a background `URLSession`** (`adapter/ios/.../TransferSessions.kt`): a task's headers
  are fixed when it is queued, which may be long before it is sent. The PhotoKit upload extension and Android
  in a plain event restart an upload from byte 0; the app's own file uploads resume (`FileUploads`,
  `docs/architecture.md`, "Resumable uploads").
- **Headers** the app sends are declared per operation in `api/openapi.yaml` and generated into the wire
  (`Routes.*.HEADER_*`); the gateway alone adds the token and the version header.

## Goals / Non-Goals

**Goals:**
- Every stage of a photo's journey, with its time, its trigger and the phone's conditions, recoverable from the
  request log alone — for 30 days, independent of the event's deletion.
- Product numbers (events, members, photos, sizes, devices × events) from the log alone.
- Operational tables hold only what operations read.

**Non-Goals:**
- A dashboard, reports or new `deno task logs` statistics beyond filtering on the new fields.
- Typed columns, indexes or views over the facts (alternative D below — a later, additive step).
- Failure stages on the phone (download or import failures stay with crash reports and Bugsink).
- Install funnel, active installs, retention cohorts, a per-install analytics id; video duration, pixel size.
- Correcting phone clocks.

## Decisions

### D1. One record: the request log's `fields`, no new table or column
Every fact is a key in the request's `fields` object (alternative A). The shape of the data decided it only
partly — most facts are 1:1 with a request, the receiver's per-photo stages are 1:n — and the wish to start with
the least schema did the rest.
- *Alternatives:* every fact a column (wide, mostly NULL, the 1:n lists do not fit); a table per fact group
  (typed, the 1:n natural, a migration per group, several inserts per request); a long fact table (untyped,
  pivots everywhere); trace tables upserted in place (simplest queries, but mutable rows beside an append-only
  log); an external analytics service (data would leave the operator's store).
- *Later, additive (D):* `ALTER TABLE request_log ADD COLUMN … GENERATED ALWAYS AS (json_extract(fields, …))
  VIRTUAL` (SQLite allows VIRTUAL on ADD COLUMN), indexes on them, and views fanning the lists out with
  `json_each`. Hence D2.

### D2. The keys are a contract from the first release
Backlog is recorded per photo where a download starts (the redirect), not on the union read: counting it there
would cost the hot read one more store round-trip for a number the redirects already give.
Generated columns and views will read them, so they are fixed and documented in `docs/architecture.md` beside
the request log: one name per fact, timestamps as the wire's `Timestamp` (whole-second UTC — the api's one time
shape), ids as plain strings, per-photo lists as arrays of objects. The caller's device id is the context's `device`
on every app request, so no route needs a header of its own for it (D7). The declared app version moves from its
column into `fields` as `app_version` — `version` is already a manifest field (its publish's version) — and the
console line still prints `v=`.

| Request | New facts (`fields`) | Source |
|---|---|---|
| every request | `app_version` | the version header |
| every app request | `device` `os` `model` `flavour` `access` `network` `mobile_data` `low_power` `background` | one context header |
| byte PUT | `taken` `discovered` `discovered_by` `queued` `queued_by` (device clock) | headers |
| byte PUT | `kind` (fresh / resume) `from` `size` `media` `backlog` | server |
| byte PUT, completing | `uploaded_all` (the last declared photo of a settled device) | server |
| upload probe | `offset` (what it served) | server |
| union read | `reader` (the verified device) `from` `to` (cursor) · `notified` `announced` (the push's union position, echoed) | server · headers |
| download redirect | `device` (the context's, unverified) `queued` `queued_by` `backlog` | headers · server |
| received | `position` · `delivered`, once per photo: `{owner, asset, downloaded, added, by}` · `caught_up` | body · server |
| manifest that took | `assets` `state` · `withdrawn`, once per photo | server |
| create | `event` `encrypted` `window_days` `open_days` `backdated` | server |
| join · leave | nothing new: method and URL say who joined or left which event, and how | — |
| sweep (not a request) | one row per event completed or deleted: method `SWEEP`, `event`, `outcome` | sweep |

### D3. Phone facts ride requests the app already makes
- *Uploader:* `taken`, `discovered` and `queued` ride the photo's byte PUT. A background session's headers are
  fixed when the task is queued, so `queued` is exact by construction.
- *Receiver:* `notified` and the echoed union position ride the read the push caused (single values).
  `downloaded` and `added` ride the **body of the `received` report** the receiver already sends once it holds
  the photos: they are a list, one entry per photo; a back-dated join delivers hundreds at once, and as a header
  of the union read (a GET) they could exceed the proxies' header limit (unmeasured on the pull zone) — which
  would refuse the read itself and stop photo sync.
- *Alternatives:* a dedicated metrics route (extra requests, its own queue and retries); pulling device logs
  (only for devices the operator holds); the lists capped in union-read headers (keeps the GET, still risks the
  read on a limit lower than guessed).

### D4. Phone clocks are trusted as they are
Phone timestamps are wall-clock, sent as such. *Alternatives:* offsets to the request (wrong for background
transfers: the header is fixed at queueing), a `device-now` skew probe on live requests (rejected as not worth
it for minute-scale stages). Accepted risk: a phone with a wrong clock corrupts its own stages.

### D5. Upload attempts are counted by the server alone
Each PUT records `kind`, `from`, `size`, bytes received, `ms` and its outcome; per-photo totals (attempts,
first-PUT-to-landing, throughput, bytes sent again) are derived when reading, by joining a photo's PUTs on its
path. A **restart** (the PhotoKit extension, Android in a plain event) is told when reading — a `fresh` PUT for a
path that already has an attempt — because the server keeps no session for those and could only tell it at write
time by querying the log itself. *Accepted:*
attempts that never reach the edge, and those the platform kills, are invisible.

### D6. Triggers are the app's entry points
Each phone stage carries the name of the `EntryPoint` that ran it (`OnForeground`, `OnSilentPush`,
`OnScheduledWake`, `OnUploadSessionEvents`, the upload extension's, …) — shared by iOS and Android, already the
`Log` facade's trigger label. App state is not sent: the entry point already says it.

### D7. The download redirect names its receiver by the context's device
The route is ungated (a browser follows it too); the context header every app request carries names the device.
Unverified because the record is diagnostic and the background session would freeze a token that may expire. A
browser sends none.

### D8. Device context on every request
`os`, `model`, `flavour`, `access`, `network`, `mobile_data`, `low_power`, `background` are added by the gateway
(and to every background transfer's headers), from the platform's own facts — the same ones the user-sent bug
report's device state reads. All builds send them; `flavour` separates test traffic.

### D9. Operational tables lose their diagnostics
The app reads the union log's `gained` rows and the event/membership clock columns, so those stay. The `fetch`
and `removed` rows, `union_log.at`, `devices.app_version`, `attested_at` and `push_updated_at` are no longer
written or read, and the `read-log` and `app-version` fault tags go with their writes. The sweep's version
table — read from `devices.app_version`, and the proof `PENDING_CLEANUPS.md` asks for before `MIN_APP_VERSION`
rises — is replaced by the request log: the distinct devices per `app_version` seen in the last 30 days, the device
read from the context, else the union read's `reader`, else the URL's device segment (an older build sends no
context, and those are the builds the proof is about). A phone in a live event is woken daily, so one silent for
30 days is the accepted gap. The sweep's job summary goes; per-item failures are no longer counted (accepted: the
next night retries them).

### D10. Stop and drop in one deploy; its rollback needs a forward fix
A migrating deploy publishes the maintenance bundle, migrates, then publishes the new bundle (`deploy.yml`), so the
new bundle never runs on the old schema: the code that stops writing the retired columns and the migration that
drops them ship together, as ONE migration. It copies `request_log.version` into `fields.app_version` (`json_set`)
before dropping the column — so no reader needs a fallback for older rows — drops `devices.app_version`,
`attested_at` and `push_updated_at`, deletes the union log's `fetch` and `removed` rows and drops the columns only
they used (`trigger`, `cursor_from`, `cursor_to`, `served`) and `at`.
- *Accepted risk:* an automatic rollback of THIS deploy puts the previous bundle on the dropped schema, which
  still writes `union_log.at` with every landing (uploads answer `502`), `attested_at` (attestation fails) and
  `push_updated_at` (push registration fails) until someone rolls forward. `docs/deployment.md` says so. The deploy
  changes little else, so it is unlikely to go red.
- *Rejected:* two deploys (stop, then drop) — a rollback of the drop still breaks the two `NOT NULL` writes
  the first bundle cannot stop; rebuilding `devices` and `union_log` to make them nullable (three deploys,
  `union_log`'s AUTOINCREMENT sequence to carry over); keeping the two `NOT NULL` columns (a compromise kept only
  to dodge a rollback case).
`DROP COLUMN` on bunny Database is not yet measured: probe it on the deployed store first, as the project
measures every platform capability (`PROBE-FINDINGS.md`); if it is refused, rebuild the table instead.

### D11. Retention stays 30 days; 12 months waits for encrypted creation
The row's age decides, not the event's. The raise to 12 months is decided but tied to encrypted event creation
(a plain event's photos are readable, which links a device id to faces): it is a step of `PENDING_CLEANUPS.md`,
"Re-enable encrypted creation", with its own spec change.

## Risks / Trade-offs

- [The record's JSON is untyped] → the key contract (D2) is documented and tested by the request-log tests;
  generated columns later make it typed.
- [Trace queries scan and parse JSON] → affordable at SnapSync's volume (order 10⁵–10⁶ rows a year); add D.
- [A wrong phone clock skews its stages] → accepted (D4); visible as negative durations.
- [Killed and never-arriving uploads are undercounted] → accepted (D5).
- [Stranded receiver facts: a member who leaves before reporting] → accepted; the report is sent whenever the
  receiver holds new photos, so only the last batch can be lost.
- [Stage facts kept on the phone until reported grow without bound if reports keep failing] → bounded store,
  oldest dropped first.
- [The `received` body grows on a large back-dated join] → a body has no header limit; split into several
  reports above a fixed count to bound the request.
- [Losing the job summary loses the sweep's per-run counts] → accepted; event outcomes are rows in the log (D2).
- [A device's id stays readable for 30 days] → disclosed in the Privacy Policy; no hashing (decided).

## Migration Plan

1. Probe `DROP COLUMN` on the deployed store (D10).
2. Deploy the api: the new facts, the stopped writes and the one migration (D10). Old app builds keep working:
   every new header and the `received` body's list are optional.
3. Release the app (TestFlight, Play internal, then stores) sending the new headers and the delivery list.
   The Privacy Policy section ships with the first release that sends them (spec: same release).
Rollback: step 2's automatic rollback needs a forward fix (D10).

## Open Questions

- The pull zone's header size limit — irrelevant to this design (D3), worth a measurement for future headers.
