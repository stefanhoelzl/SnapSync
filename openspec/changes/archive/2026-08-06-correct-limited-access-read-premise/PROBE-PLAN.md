# Probe plan — does a scope-unambiguous PhotoKit fetch alert under `.limited`?

Device: iPhone SE2 `00008030-0018703A1A7A402E`, **iOS 26.5.2**. Under `.limited` the ≥26.1 extension is
never invoked (CLAUDE.md fact ②), so `UploadArm` runs the URLSession producer **in-app** — every site
below fires in the app process and lands in `Documents/debug.log`.

## Why this probe exists

`fix-duplicate-import-on-restart` found **six ungated PhotoKit reads** reachable under `LIMITED`. Four
can be gated for free. Two — the event-album lookups at `IosPhotoLibraryImporter:103` and
`IosAlbumManager:129` — **are** the `event-album` feature: gate them and a limited member's imports and
uploads silently never join the album, contradicting the `limited-photo-access` requirement that *"a
limited member's opted-in album exists before their first import lands"*.

Choosing between "fetch (unknown alert risk)" and "drop event-album under LIMITED" is not decidable from
the existing record. `PROBE-FINDINGS.md` measured only `PHAsset.fetchAssetsWithOptions` — a library-wide
walk, maximally scope-ambiguous — and its own explanatory hypothesis (*"the alert concerns read scope,
and a created asset auto-joins the selection with no scope ambiguity"*) predicts the opposite result for
a by-identifier fetch. That cell was never measured.

## Probe SHAPES, not sites

The six sites reduce to four API shapes. Sites sharing a shape stand or fall together, which removes the
need for a foreign photo (and therefore a second device or a seeded backend) for most of the ladder.

| shape | API | sites |
|---|---|---|
| **S0** | `PHAsset.fetchAssetsWithOptions` | the walk — **known to alert**; the positive control |
| **S1** | `PHAsset.fetchAssetsWithLocalIdentifiers` | #4 `logImportedDate` · #9 `IosAlbumManager.add` |
| **S2** | `PHAssetCollection.fetchAssetCollectionsWithLocalIdentifiers` | #5 importer album lookup · #6 `exists()` |
| **S3** | `PHAssetCollection.fetchAssetCollectionsWithType` | #7 denylist album scan |
| **S4** | `PHAsset.fetchAssetsInAssetCollection` | #8 denylist members |

⚠️ **Stated assumption:** S1 covers #4 by equivalence of *API and argument shape*, but #4 fetches an
asset created microseconds earlier **in the same commit**, whereas #9 fetches assets long resident in the
selection. If the alert depends on *recency of a selection change* (hypothesis H3, Apple's engineer: the
key does not suppress the alert "when the selection changes"), those differ. **L4 addresses this**: seeded
policy assets are created (a selection change) and then uploaded in the same wake, so `place()`'s S1 fetch
lands shortly after a selection change — the nearest reachable analogue of #4's timing.

## Detection oracle

Alerts **queue and survive app death** — `PROBE-FINDINGS.md` observed them draining onto the bare home
screen with no process in `proclist`, each needing a manual "Keep Current Selection". That converts a
yes/no glance into a **count**, which is what the previous probe lacked (its "steady state is clean at
15:08" was a single-frame timing artifact).

```
  drain queue by hand ──▶ dvt screenshot ──▶ MUST show a clean home screen
                                │
                          dvt launch --env <run's directives> --userspace
                                │
                          apps pull Documents/debug.log
                          verify the op ACTUALLY RAN (table below)
                                │
                          dvt signal <pid> 9          ← SIGKILL; SIGTERM is ignored
                                │
                          dvt screenshot, repeatedly, dismissing by hand
                                │
                          COUNT the alerts that drain
```

Capture `idevicesyslog` in parallel on **P0**: if alert presentation emits a distinctive line, every later
run becomes fully headless.

### Per-site proof the operation ran (so "no alert" ≠ "no call")

| site | `debug.log` oracle |
|---|---|
| S0 | `policy probe: subtype census — library total=…` |
| #4 (S1) | `imported <id> creationDate(actual)=… intended=…` |
| #5 (S2) | **absence** of `event album <id> no longer resolves — camera roll only` |
| #6 (S2) | `ensureAlbum: reused album=…` vs `… no longer resolves — recreating` |
| #7/#8 (S3/S4) | `denylisted album '<title>': N member(s) in scope` |
| #9 (S1) | `place: added N asset(s) to album=…` |

Those same lines answer **Q3 — is album structure readable under `.limited`?** independently of alerts.
CLAUDE.md asserts *"album structure is unreadable"*; it was never measured, and `event-album` under
`LIMITED` may be broken for reasons unrelated to alerts.

## The ladder — each rung adds exactly one shape

| run | grant | env / membership | adds | needs backend? |
|---|---|---|---|---|
| **P0** | LIMITED | `SNAPSYNC_POLICY_PROBE=<iso cutoff>`, unjoined | **S0** — positive control | **no** |
| **L1** | LIMITED | unjoined, no directives | nothing — **negative control** | no |
| **L2** | LIMITED | join, `direction=download`, `saveToAlbum=true` | **S2** (`ensureAlbum`) | yes |
| **L3** | LIMITED | join, `direction=both`, `saveToAlbum=false`, ≥1 photo selected | **S3, S4** (denylist, per cycle) | yes |
| **L4** | LIMITED | join, `direction=both`, `saveToAlbum=true`, `SNAPSYNC_SEED_POLICY=4` | **S1** (`place` after completions, shortly after a selection change) | yes |

`SNAPSYNC_SEED_POLICY` is used in L4 rather than `SNAPSYNC_SEED_PHOTOS` because the latter's assets are
2001-dated and 64×64 — policy-excluded, so they never upload and `place()` never fires.

**P0 is mandatory and must run first.** If P0 shows no alert the detector is broken and every other
result in the session is void.

## Pre-registered decision rules

Committed *before* the run. "Dirty" = **≥1 alert after a verified-drained start**. No judgement calls.

| result | conclusion | consequence for the change |
|---|---|---|
| **P0 clean** | detector broken | **discard the whole session** |
| **L1 dirty** | harness itself alerts | discard; isolate the source first |
| L2 clean | S2 safe | **#5, #6 stay** — `event-album` survives under `LIMITED` |
| L2 dirty | collection-by-id alerts | #5/#6 must go → **no album placement for limited imports**; spec states the limitation |
| L3 dirty vs L2 | S3/S4 alert | gate #7/#8 — free, the denylist is already inert under `LIMITED` |
| L4 clean | S1 safe (incl. post-selection-change timing) | **#4 and #9 stay**; and the guard **may fetch under `LIMITED`** → design collapses to two-valued, no permission-aware wrapper, `UNKNOWN` disappears |
| L4 dirty | asset-by-id alerts | keep the snapshot design; **#4 and #9 must go** |

Independently of alerts, from `debug.log`:

| observation | conclusion |
|---|---|
| `ensureAlbum: … no longer resolves — recreating` on every launch | S2 returns nothing under `LIMITED` → album identity unrecoverable → `event-album` is broken there regardless |
| `event album … no longer resolves — camera roll only` | imports never join the album under `LIMITED` |
| `denylisted album …` absent while a denylisted album exists | S3 blind under `LIMITED` → the inertness claim confirmed |

## Operational prerequisites

**Human steps (WebDriverAgent is gated here, so taps are not scriptable):**
1. Set Photos access for SnapSync to **Limited** (Settings → Privacy → Photos), selecting ≥2 photos of
   which ≥1 is above the 3 MP floor (L3/L4 need something uploadable).
2. Drain the alert queue before **every** run, and count/dismiss after.

**Build:** the currently installed `0.1 (1)` is dev-signed but points at a **dead quick tunnel**
(`demonstration-classification-adrian-feel.trycloudflare.com`), so **L2–L4 need a rebuild** —
ssh-mac, `-configuration Debug`, `BACKGROUND_UPLOAD_URL_BASE` pointed either at production
(throwaway event, `SNAPSYNC_CREATE_EVENT`) or a fresh `deno task dev:tunnel`. The 6b re-sign applies
unchanged. `SNAPSYNC_RESET_STATE=1` on the first launch after any backend change.

**P0 and L1 need no backend and no rebuild** — they run against the installed build today.

## Session hygiene

- The device is shared across seven worktrees. This probe flips a **global** device setting (`LIMITED`)
  and deliberately induces alert storms; any concurrent run by another workspace corrupts the counts in
  both directions. Confirm exclusivity before starting and restore the grant to **Full Access** after.
- The installed build carries `spike:` instrumentation present in no worktree. Reinstalling evicts it —
  confirm nobody needs it.
- Record every run's raw `debug.log` and screenshots under `scratchpad/probe/<run>/` so the findings can
  be re-read later, the way `PROBE-FINDINGS.md` can be today.
