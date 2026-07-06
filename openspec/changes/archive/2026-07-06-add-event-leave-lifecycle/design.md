## Context

Leave is local-only today (`leave-event`): `LeaveEvent.leave()` runs `disable producer →
ConfigStore.clear()` and touches no backend state. Every uploaded object, per-event manifest, and
device config persists indefinitely — the design's §1/§3.2 explicitly guarantee leave "touches no
storage." This change reverses that guarantee and makes leave an event-lifecycle operation.

The storage substrate is **plain Bunny native Storage** (`backend/src/app.ts`, Hono/Deno) — no
transactions, no atomic rename, per-object DELETE, no batch delete. Reads from the **main** region
are read-after-write consistent (documented deployment invariant, `backend/README.md`), which the
join/marker/upload flow already depends on. The device is single-event locally (holds one `eventId`;
switching re-provisions), but an event is multi-device, and uploaded bytes are **device-global and
shared** (`files/devices/<D>/…`), referenced indirectly by each event's per-device manifest. All
paths below assume the post-`restructure-storage-url-layout` layout.

## Goals / Non-Goals

**Goals:**

- Remove a departing device from an event without stranding the remaining members' downloads.
- Reclaim an event's storage when its last device leaves — reap the event and GC bytes that no
  surviving event references.
- Do it correctly on dumb object storage: no corruption of in-use data under any partial failure or
  concurrent-operation race.
- Reuse the existing seam patterns (best-effort device-side use-case; HTTP membership seam; MiniEdge
  world model) and the existing main-region consistency invariant.

**Non-Goals:**

- A leave-confirm dialog on switch (separate change). Switch leaves silently in this change.
- A periodic backend reaper for the abandon-leak (separate change). The abandon-leak is accepted.
- Multi-event membership on the device. The device stays single-event; "last device" is an
  event-side property.
- A byte-download proxy or any change to upload/download crypto, presigned URLs, or the
  deviceId-as-capability trust model.

## Decisions

### 1. Event-centric lifecycle, not device-centric deletion

Deletion keys off an **event's** active-member count reaching zero, not off "the last event a device
joined" (which is degenerate — the device is single-event). This sidesteps the single-event/multi-event
mismatch entirely. *Alternative considered:* device-tracked set of joined events → rejected (device is
single-event; multi-event is deferred).

### 2. Leave = rename `<D>.json` → `<D>.left.json`; last active leaver reaps

Active membership = presence of `events/<E>/devices/<D>.json`. Leaving **renames** it (copy then
delete). A departed device keeps a `<D>.left.json` manifest so the union still serves its photos.
"Last device leaves" = no active manifest remains under `events/<E>/devices/`.

*Alternatives considered:* (a) delete the manifest on leave → rejected: departed device's photos
vanish from the union and its bytes become GC-eligible, breaking remaining members' downloads.
(b) a separate `active/<D>` marker written on join → rejected: forces a backend call on join (today
join is a purely local QR scan) and adds a namespace. The suffix rename needs no join-time call and
doubles as the rejoin signal. (c) a single merged per-event `left.json` accumulator → rejected:
concurrent leaves race on read-modify-write; per-device files never conflict.

### 3. Membership is last-write-wins over the two siblings' write times

When both `<D>.json` and `<D>.left.json` exist (a partially-applied leave or rejoin), the **newer**
object wins:

```
active(D)   = <D>.json present ∧ (<D>.left.json absent ∨ <D>.json.LastChanged > <D>.left.json.LastChanged)
departed(D) = <D>.left.json present ∧ ¬active(D)
```

This dissolves the symmetric-partial ambiguity: a stalled *leave* leaves the newer `.left.json`
(→ departed, correct — event can still reap); a stalled *rejoin* leaves the newer `.json` (→ active,
correct — event won't reap under it). The surviving-but-stale sibling is always the older one, so it
always loses. The timestamp is already in every Bunny LIST response (discarded today at app.ts:126) —
add `LastChanged` to `BunnyEntry`; **zero extra requests**. An exact-tie (impossible in practice — the
two writes are on different paths, separated by a human gesture) defaults to `active` (leak-safe side).

Consequence: the losing-sibling **deletes are cosmetic**. Writing the newer sibling is the commit;
the old one just needs to lose the comparison, not be gone. So **no delete-retry loop and no reaper
are needed for correctness** — a lingering old sibling is inert and gets swept at event death.

*Alternative considered:* pick a fixed interpretation of "both present." "Both = active" leaks on a
stalled leave; "both = departed" can reap an event out from under an actively-rejoined device
(**corruption**). LWW is strictly better than either.

### 4. The cascade runs synchronously inside the DELETE handler; the device fires once, best-effort

```
DELETE /events/<E>/devices/<D>   (idempotent, at-least-once safe)
  1. PUT   events/<E>/devices/<D>.left.json  ← copy content of <D>.json (FRESH LastChanged = commit)
  2. DELETE events/<E>/devices/<D>.json      ← cosmetic cleanup (LWW already makes D departed)
  3. LIST  events/<E>/devices/ : any active(·) member (LWW)?
       if none:
         DELETE events/<E>/**                              (marker + all .left.json)
         for each device D' freed by that deletion:
           if D' appears in NO surviving event (active OR departed, across LIST events/):
             LIST files/devices/<D'>/ → DELETE each object  (no batch delete)
             DELETE devices/<D'>.json                        (config)
```

Step 1 mints a **fresh** timestamp — a read-then-PUT, never a metadata-preserving server-side copy.
The device fire-and-forgets (best-effort), so cascade latency is invisible to it; a mid-way timeout
just leaves orphans (accepted). The handler is idempotent — "ensure `.left.json` exists, ensure
`.json` gone, then conditionally reap" — so any retried/duplicate DELETE re-runs harmlessly.

*Alternative considered:* deferred GC via a second call or `/gc` endpoint → rejected: relies on a
second call landing (fragile with no reaper) for no benefit at this scale.

### 5. GC is a true reference-count across all events

A device's `files/devices/<D>/` partition is deletable only when D appears in **no** surviving event,
active **or** departed. After a switch, D is `.json` in the new event but `.left.json` in the old one,
and the old event's union still serves D's bytes — so "D left its last *active* event" is **not**
sufficient. GC re-checks membership across `LIST events/` before deleting each freed device's bytes +
config. Byte partitions are single-writer (only D's manifests reference `files/devices/<D>/`), so a
device's bytes can never be needed by another device.

### 6. Device wiring: best-effort step in the renamed `:capability:membership`

`LeaveEvent` gains a `notifyLeave(eventId, deviceId)` step (a new `LeaveNotifier` /
`HttpLeaveNotifier`, mirroring `DeviceFilesSource`), fired from the main app (which holds the Darwin
`HttpClient`). Order: `disable producer → notify-leave → clear config` (read `eventId` before
clearing). A failed call still completes local teardown (best-effort, no rollback) and widens the
accepted leak. Both the explicit Leave button and the **switch** path (provisioning a different event
while joined, via `deeplink-config`) call the same notifier; switch leaves the previous event before
re-provisioning. `:capability:rejoin` is renamed `:capability:membership` to honestly cover
join-reconcile + leave + listing.

## Risks / Trade-offs

The design rests on two properties. Everything else is a consequence.

- **[Leak-safe by construction.]** Every partial failure and race resolves toward an **orphan**
  (something undeleted), never toward destroying in-use data → absorbed by the accepted abandon-leak.
  Copy-before-delete in the rename; LWW resolving both-present; stale LIST reads biasing toward "not
  reaping." *Mitigation:* none needed — this is the safety property itself.

- **[Corruption-free given main-region read-after-write LIST consistency.]** The one path that could
  reap an event under an actively-rejoined device (a stale reap LIST missing a concurrent rejoin's
  fresh `.json`) is closed by the **existing deployment invariant** (`BUNNY_STORAGE_HOST` = main
  region). *Mitigation:* name the reap as a dependent of that invariant in `backend/README.md`;
  never route reap reads to a replica. Even under a hypothetical stale read, the effect is bounded —
  the rejoining device's manifest PUT is marker-gated, so it fails against a reaped event rather than
  corrupting another device's data.

- **[GC vs the leaving device's own rejoin.]** A GC concurrent with the owning device's rejoin can
  briefly dangle **that device's own** keys. *Mitigation:* none needed — byte-partition single-writership
  makes cross-device corruption impossible; the owning device's next reconcile re-uploads the byte,
  and the download path already tolerates a 404 resource (stays pending, retried next reconcile —
  `DownloadController.reconcile`), so the window is at most a delayed import of the device's own photos.

- **[Abandon-leak.]** Devices that vanish without a clean leave (uninstall, permanent offline) never
  trigger a reap; the event and its bytes leak. *Mitigation:* accepted for v1 (personal app, cheap
  storage); a periodic reaper is a deferred separate change.

- **[Switch leaves silently.]** Until the follow-up leave-confirm-on-switch dialog ships, re-scanning
  a different event's QR silently leaves the current event. *Mitigation:* accepted intermediate state;
  the confirm dialog is a scoped follow-up.

- **[Big breaking surface.]** A new device→backend `DELETE`, LWW membership across union/notify/reap,
  and a module rename land together. *Mitigation:* ships behind `restructure-storage-url-layout`; app
  and backend deploy together (device is the sole client, pre-release); `:test:integration` asserts
  the full leave → reap → GC outcome over the MiniEdge world.

## Migration Plan

1. Land `restructure-storage-url-layout` first (prerequisite; all paths here assume it).
2. Backend: `BunnyEntry.LastChanged`, LWW membership in union + notify, the `DELETE` route + cascade
   (rename → reap → reference-checked GC), `backend/test`, `backend/README.md`.
3. Device: rename `:capability:rejoin` → `:capability:membership`; add `LeaveNotifier` /
   `HttpLeaveNotifier` + `LeaveEvent` step; wire the switch path in `deeplink-config`; app composition
   root.
4. Harness: MiniEdge cascade + LWW; `:test:integration` leave/reap/GC outcomes.
5. Docs/specs: `docs/design.md` §3/§3.2; the 1 new + 7 modified specs; incidental module-name prose.
6. Verify: `deno task test` + `deno fmt/lint`; `./gradlew build`; `compileIosMainKotlinMetadata`;
   `openspec validate --specs --strict`. Deploy backend + app together, wipe the zone, drive the
   headless dev loop: join → leave → confirm the event tree + byte partition are gone in the bunny
   zone. **Rollback:** revert the branch and redeploy; zone is disposable.

## Open Questions

None — resolved in the design interview and the leak-safety/consistency exploration. The two risk
notes (GC-vs-own-rejoin; reap depends on main-region consistency) are documented, not open.
