## Context

Today a decoded `snapsync://` deeplink provisions immediately: `SnapSyncRoot.provisionEvent` saves
the config, enables the upload producer, and fires a best-effort `GET /events/:id` for the name
*after* the fact. There is no confirmation, and the device is invisible to the event (not enumerated
under `events/<id>/devices/`, so not counted and not reachable by the notify fan-out) until its first
upload cycle writes a manifest.

This change inserts a **confirmation gate** and makes join a first-class, server-acknowledged
action. Constraints shaping the design:

- **Design-system containment** — the join screen uses only `App*` semantics; M3 stays in
  `:domain:ui:components`.
- **DI, not `expect`/`actual`** — the `JoinEvent` use-case is pure `commonMain`; platform effects
  (enrollment PUT, enable-upload) are injected lambdas/seams.
- **iOS constrains `commonMain`** — no JVM-only APIs in shared code.
- **Two entry doors** — the presentation container's `onOpenUrl` and iOS
  `SnapSyncRoot.onOpenUrl`; the gate must be shared, not duplicated.
- **A parallel workspace** is adding a backend leave call to `LeaveEvent`. This change must
  **compose** `LeaveEvent`, never edit its spec or code, so the two land without conflict.

## Goals / Non-Goals

**Goals:**
- An explicit, full-screen join confirmation that is **extensible** — future options (start date,
  direction, albums, save-to album) drop in as rows, no rework of the surface or state.
- A join that is **server-acknowledged**: event existence is validated (GET) before confirm, and
  membership is registered (enrollment PUT) before the local commit.
- One shared gate across both entry doors; a dev/test path that keeps the headless loop working.
- Full `:test:integration` seam→UI-state coverage.

**Non-Goals:**
- Building any of the future options now — only the **surface** is made ready (no `JoinOptions`
  type, no option UI).
- Editing `LeaveEvent` / the `leave-event` spec (owned by the parallel workspace).
- Adding backend endpoints — reuses `GET /events/:id` and `PUT /events/:id/devices/:deviceId`.
- Routing the **switch** path through the full-screen options surface (deferred until options exist;
  switch stays a leave-style confirm for now).

## Decisions

### D1 — Full-screen surface, not a dialog
The join surface is a dedicated **`JoiningEvent` screen** built on `ScreenLayout`, mirroring the
create-event screen. *Alternatives:* a centered `AppConfirmDialog + content` (like leave) or a bottom
sheet. Rejected because the stated future options are form-heavy (album multi-select, date picker,
save-to album); a centered dialog crowds and forces its options into a small scroll window. A full
screen has unlimited vertical room and matches an existing pattern. Switching (a rarer teardown) still
uses `AppConfirmDialog` — it is not the options surface.

### D2 — Join state lives in presentation / `UiState`
A new sealed family **`JoiningEvent(eventId, name: Loading | Loaded | NotFound | Failed)`** drives the
screen; **`pendingSwitch`** on `Joined` (same `name` phases) drives the switch dialog. The container
owns the **decode-and-route gate**. *Alternative:* local Compose state like `confirmingLeave`.
Rejected — a join carries data (eventId, fetched name, switch-vs-first, load phase) and must be
covered by the seam→UI-state integration tests and shared by both doors; bare local state can do
neither. `sync-status-screen` already admits an externally-owned UiState family (the create layer,
owned by `event-creation-ui`), so `JoiningEvent` (owned by `join-event`) follows precedent.

### D3 — Loading gates the confirm; GET is the existence check
The screen opens instantly on decode (eventId is local), then `GET /events/:id` runs behind
"Loading event details…". **404 → blocked** ("invalid/expired invite", no Join); **network/502 →**
error + **Retry**; **200 →** the confirm (name + Join/Cancel). This turns the previously-absent
existence check into the load gate. *Alternative:* non-blocking name with a fallback + always-enabled
Join. Rejected per the user's chosen flow — a confirmed-existing event is required before enrolling.

### D4 — Confirm = enrollment PUT first, commit on success
On confirm: **`PUT /events/:id/devices/:deviceId`** with a **register-only empty manifest**
(`{ assets: [] }`) → on 201, `config.save` + enable upload → `Joined`; on failure, stay on the surface
with an error + **Retry**, nothing persisted (no half-joined state). The empty manifest exists only to
make the object present under `events/<id>/devices/`, so the device is an enumerable, notifiable member
*immediately* — closing the "joined but invisible until first upload" gap. The real asset manifest is
written later by the normal upload cycle (last-write-wins). *Alternatives:* (a) write the full
date-filtered projection now — rejected: a full library walk on the join tap, duplicating the upload
cycle; (b) commit locally and enroll best-effort — rejected: leaves a locally-joined-but-not-a-member
window.

### D5 — Switch = `leave` ∘ `join`, composed
Scanning while joined shows `AppConfirmDialog` ("Leave X and join <name>?"); confirm runs
`leaveEvent.leave()` **then** `joinEvent.join(Y)`. The switch is pure composition of two independent
use-cases in the container's injected lambdas — `LeaveEvent` is **never modified**. So when the
parallel workspace's backend-leave lands, the switch automatically becomes
**get / dialog / leave-endpoint / join-endpoint** with no change here. The switch dialog is also
GET-gated (it needs Y's name), so `pendingSwitch` shares the `Loading | Loaded | NotFound | Failed`
phases.

### D6 — Partial-failure on switch: leave→join, Retry recovers
Order is **leave X, then join Y** (the user's stated sequence). If leave succeeds but the join PUT
then fails, the device is transiently in **no event**; the surface shows an error + **Retry** that
re-runs **only `join(Y)`** from the remembered pending target. Cancel leaves the user event-less until
they re-scan. *Alternative:* join-then-leave (no event-less gap, but a stale double-membership on a
failed leave) — rejected to honor the stated leave→join ordering.

### D7 — `autoJoin` is a payload field that auto-confirms the gate
`EventLinkPayload` gains `autoJoin: Boolean = false` (inside the `d` blob, the user's chosen
placement). When true, the gate runs **identically** — still decodes, still GETs, still enrolls,
still leaves-first when already joined — but **auto-fires the confirm** once details `Loaded` instead
of waiting for a tap. This keeps the headless on-device loop working (it cannot tap Join) while
letting the same env-launch also exercise the interactive path (omit the flag). On the dev path there
is no UI, so a 404 / network / PUT failure **aborts and logs** rather than parking on an error state.
*Alternative:* a top-level `&autojoin=` query param (ignored by old decoders — zero back-compat risk).
Rejected by the user in favor of the payload field; the back-compat caveat is captured as a risk.

### D8 — `join-event` is one capability, mirroring `leave-event`
The use-case, screen, `JoiningEvent` state, switch confirmation, enrollment, and `autoJoin` behavior
live in a single `join-event` capability. *Alternative:* split logic vs UI (as `event-creation` /
`event-creation-ui`). Rejected — `leave-event` is the closer analog and bundles use-case + affordance
+ confirmation in one capability; `autoJoin` only serves this gate, so a separate codec-only change
would be dead code until the gate lands.

## Risks / Trade-offs

- **Empty-manifest clobber** → The device manifest now has two writers: the app (empty, at
  enrollment) and the extension upload cycle (real projection). A re-scan / re-provision of the
  event the device is **already in** must **not** re-fire the empty PUT, or it wipes the real
  manifest back to empty until the next cycle. **Mitigation:** enrollment fires **only on a genuine
  new join** (config empty) or a **switch to a different** eventId — never on same-event
  re-provision (which is a no-op, consistent with `event-rejoin-reconciliation`'s
  "re-provision of an already-joined event is a no-op"). Covered by an explicit requirement +
  scenario in the `join-event` spec.
- **`autoJoin` rejected by pre-change builds** → the decoder is strict; a link carrying `autoJoin`
  fails to decode on any build shipped before this change. **Mitigation:** `encodeConfigUrl` (real
  invite QRs) never emits it, and only hand-crafted dev links set it — opened only by new dev
  builds. No production link is affected.
- **Transient no-event window on a failed switch** (D6) → recoverable by Retry, but a Cancel there
  leaves the user event-less. **Mitigation:** the pending target is remembered so Retry re-runs only
  the join; the empty-manifest re-scan flow can rejoin cleanly.
- **Coordination with the parallel leave change** → the switch's "leave-endpoint" step depends on
  `LeaveEvent.leave()` remaining the single leave entry point and its backend call being awaitable +
  failable. **Mitigation:** this change composes `leave()` as a whole and edits neither its code nor
  spec; a note in the proposal flags the dependency to the other workspace.
- **Enrollment reuses the device-manifest PUT with an empty body** → the manifest schema and its
  consumers (union read, notify) must tolerate an app-written `{ assets: [] }`. The union read is
  complete-only so an empty manifest contributes nothing; the notify fan-out enumerates the object's
  presence, not its contents. **Mitigation:** validated against `device-manifest` /
  `bunny-list-endpoint` / `event-notify-endpoint` during implementation; no schema change expected.
