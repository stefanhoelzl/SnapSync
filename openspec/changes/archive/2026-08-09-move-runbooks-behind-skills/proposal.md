## Why

`CLAUDE.md` is 97,732 characters (~24.4k tokens) and is loaded into **every** session in **every**
workspace, regardless of task. About 60% of it is operator runbooks — driving a connected iPhone,
the ssh-mac build loop, the harness driver, App Store Connect portal chores, the local backend rig —
that a minority of sessions ever open. Measured across 133 workspaces: the on-device runbook was
touched by 30%, ssh-mac by 44%, App Store promote by 20%, the marketing screenshots by 15%, the
harness driver by 19%, the local rig by 6%.

Worse than the volume: a large share of that text **restates contracts that already exist**. The App
Store promote and TestFlight sections re-tell `ios-appstore-release`, `ios-testflight-delivery` and
`ios-appstore-metadata`; the local backend rig re-tells `api/README.md`'s *Develop & test*; the
launch-trigger prose re-tells `ios-app-shell`. Those duplicates are unguarded, and the repo already
knows what that costs — `LawsDigestTest`'s own rationale records that "the previous CLAUDE.md module
graph rotted silently for months precisely because nothing held it to anything". The drift is
already present: `SNAPSYNC_POLICY_PROBE` is a real launch trigger in production Kotlin
(`LaunchDirectives.kt`, `SnapSyncRoot.kt`), is named in CLAUDE.md's ordering chain, and is
documented nowhere.

The countervailing evidence is that the **inline trap warnings work**. Across 20,518 Bash commands
scanned for the exact mistakes CLAUDE.md warns about, there was 1 interpolated `NSLog`, 1
`--kill-existing`, 0 uses of `java.awt.Robot` against the real screen, 0 hardcoded harness-driver
ports, 0 whole-zone storage resets. So this change must shrink the file **without** dissolving the
warnings that are earning their place.

## What Changes

- **Five project skills** are added under `.claude/skills/` — `ios-device`, `ssh-mac-build`,
  `ui-harness`, `asc-portal`, `local-backend` — each owning one trigger. They are dev
  infrastructure: non-gating, no spec, the same posture as `bugsink`, `ssh-mac.yml`,
  `:test:harness-driver` and the local rig. They sit beside the generated `openspec-*` skills
  without colliding: `openspec update` rewrites only its own generated set.
- **CLAUDE.md loses the runbook sections** and gains a `Runbooks` pointer block — one imperative
  line per skill, keyed on the intent a session forms on its own, with the commands as a tail.
- **Duplicated prose is deleted, not moved.** TestFlight delivery and App Store promote collapse to
  ~15 inline operator lines plus pointers at `ios-testflight-delivery` / `ios-appstore-release`;
  the marketing-screenshot section keeps only its dispatch block, "eyeball them" and "only `create`
  re-diffs"; the local rig points at `api/README.md`.
- **A repo-layout block** is added at the top: one line per top-level directory, an explicit
  "there is no `backend/` — it split into `api/` + `site/`" (10 measured `cd:` failures), and a
  pointer at `ls openspec/specs/` for capability names (6 measured stale spec paths).
- **The `ch-bg` background wrapper is documented** (adjacent finding C1). Three of the five skills
  start long-lived background processes and are wrong without it.
- **`app/ios/CLAUDE.md` loses its tier-forcing runbook** (~2.8k) to `ios-device` and keeps its
  architecture. That runbook is device procedure that only loads when a session touches
  `app/ios/` — which a session driving the SE2 never does, so today it hides exactly where it is
  needed.
- **Two new architecture guards** hold what remains: pointer integrity, and launch-trigger index
  freshness. Traps whose mistake is unreachable without loading the owning skill travel **with**
  the skill; only ungated traps stay inline, plus one named exception whose damage lands on a third
  party.

## Capabilities

### New Capabilities

None. The skills are dev infrastructure and take no spec, consistent with every other non-gating
tool in this repo (`bugsink`, `ssh-mac.yml`, `:test:harness-driver`, `api/src/dev/`).

### Modified Capabilities

- `architecture-guards`: two new requirements — (1) every skill named in CLAUDE.md's runbook
  pointer block resolves to an existing `.claude/skills/<name>/SKILL.md`, so a renamed or deleted
  skill cannot leave a pointer that silently reaches nothing; (2) the `SNAPSYNC_*` launch-trigger
  index carried by the `ios-device` skill equals, as a set, the `SNAPSYNC_*` literals in production
  Kotlin — the `LawsDigestTest` pattern applied to the one duplicate this change deliberately
  keeps.

## Impact

- **Docs**: `CLAUDE.md` (97,732 → ~35,800 chars, ~9k tokens); `app/ios/CLAUDE.md` (loses ~2.8k).
- **New files**: `.claude/skills/{ios-device,ssh-mac-build,ui-harness,asc-portal,local-backend}/SKILL.md`.
- **Tests**: `:test:architecture` gains the two guards; both gate `./gradlew build`.
- **Specs**: `architecture-guards` delta only.
- **No production code changes.** No module, port, feature, flow or composition is touched; no
  shipped behavior changes.
- **Known red-on-arrival**: the index guard fails until `SNAPSYNC_POLICY_PROBE` is documented in
  the `ios-device` skill. Closing that documentation gap is part of this change.
