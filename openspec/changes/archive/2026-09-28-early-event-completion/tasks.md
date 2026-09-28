## 1. Backend schema and lifecycle rules

- [x] 1.1 Migration: `events.closed_at`, `events.completed_at`, `events.last_landed_at`, `memberships.final` (all nullable, no backfill); a (re)join clears `final` beside `manifest_version`
- [x] 1.2 `lifecycle.ts`: pure `clockMs(event)` = `max(endsAt, lastLandedAt) + 3 d`, and the sweep verdicts COMPLETE (EMPTY, or clock passed on an ever-joined event) vs DROP (deadline); unit tests incl. never-joined, back-dated and created-early events
- [x] 1.3 Byte route: stamp `last_landed_at` for every event in `eventsCompletedBy`, in the same batch as `recordResource`

## 2. Backend: final flag, closing and refusals

- [x] 2.1 v2 manifest publish: parse optional `final: boolean`; store it; when the publish leaves every active membership final, stamp `closed_at` in the same batch and fan out `notifyMembers` once
- [x] 2.2 Closed event: manifest publish with a changed asset set → `409 {error:"closed"}`, identical set → 200 no-op
- [x] 2.3 Closed/completed event: join (v2 + v1 publish-enroll) and rename → `410 {error:"closed"}`
- [x] 2.4 `GET /events/:id` and the union listing: add `closedAt`, `completedAt`, and `{active, final}` counts; a completed event answers 200 with `completedAt`
- [x] 2.5 `DELETE …/devices/:d` answers 200 on a completed event
- [x] 2.6 Event web page / zip: a completed event reads as an expired link
- [x] 2.7 api tests for 2.1–2.6 (`api/test`), and the Backend port contract clauses in `:test:contracts` for the new fields and refusals

## 3. Sweep

- [x] 3.1 Event phase: COMPLETE = set `closed_at` if null, set `completed_at`, delete memberships (cascade `event_assets`), keep the row; DROP at the deadline as today; decisions stay inside the primary transaction
- [x] 3.2 Verify the asset phase collects a completed event's bytes (no references, no floor) and the device-record phase treats its devices as membership-less
- [x] 3.3 Sweep tests for EMPTY-before-close, EMPTY-after-close, clock with a silent member, deadline drop of a completed row

## 4. App: finality and state

- [x] 4.1 Model: `final` on the device manifest body; event details/union carry `closedAt`, `completedAt`, member counts (Backend port, `HttpBackend`, `BackendMock` with the api's rules)
- [x] 4.2 `DeviceManifestProducer`: set `final` once `endsAt` has passed and the projection comes from a discovery that ran after `endsAt`; the dedupe then publishes it once
- [x] 4.3 Persist the closed state and member counts in the membership config through `MembershipRefresh`, fed by the foreground refresh and by the end-of-wake details read (design D6 — the union route is unchanged)
- [x] 4.4 Handle a `409 closed` publish: no retry storm (treat as published for the dedupe marker)

## 5. App: done = leave

- [x] 5.1 Pending-leave record (SHARED-area file) written by `LeaveEvent` before the teardown; retried on foreground, silent push and BG task; cleared on 200, 404 or completed
- [x] 5.2 Auto-leave unit at the end of the tail and after the foreground refresh: closed ∧ own ledger fully uploaded ∧ every foreign union asset settled with nothing pending/staged → `LeaveEvent` without dialog
- [x] 5.3 `completedAt` set → leave, foreground and background; a bare 404 keeps today's past-the-deadline rule
- [x] 5.4 Diagrams regenerated (`./gradlew architectureDiagrams`; no flow changed) and :test:integration journeys over the JVM rig host (`EventCompletionIntegrationTest`): settle → close → leave; an unsettled member keeps the event open; a completed event ends the membership from a background wake; an offline leave is delivered by a later wake. A `backend/complete` rig lever plays the sweep's completion

## 6. App: UI

- [x] 6.1 Joined screen of a closed event: only name, status line and Leave (no QR, share, settings, rename); forge harness preset for it
- [x] 6.2 Ended line: "waiting for N of M members" while not closed and "In sync"
- [x] 6.3 Join screen: `410 closed` → "can no longer be joined", Cancel only (switch keeps the current event)
- [x] 6.4 UI tests in `:ui:screens` for 6.1–6.3

## 7. Docs and policy

- [x] 7.1 `docs/architecture.md`: event lifecycle (final, closed, completed, clock), sweep rules, manifest `final`, pending leaves, the close wake
- [x] 7.2 Privacy Policy on `site/`: how long photos are kept (deleted once finished, at most 30 days)
- [x] 7.3 Mission paragraph in `openspec/config.yaml` and its copy in `CLAUDE.md`: a late guest joins until the event closes; how an event ends

## 8. Specs at sync time

- [x] 8.1 Rewrite the `## Purpose` paragraphs the deltas cannot touch: `event-lifetime`, `manage-membership`, `join-event`, `sync-status`, `photo-sharing`
- [x] 8.2 Run both archive gates from `openspec/config.yaml` (placeholder Purpose, no code identifiers) and `validate --specs --strict`
