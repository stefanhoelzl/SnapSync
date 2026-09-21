## Why

`limited-photo-access`, and `CLAUDE.md` rule ① that restates it, tell every agent that under a partial
grant **"every photo the member takes costs one system prompt"**, which no read strategy avoids. That comes
from one camera photo on one device on iOS 26.5.2. It also leaves out the fact that makes the observation
odd: the app ships `PHPhotoLibraryPreventAutomaticLimitedAccessAlert = true`, Apple's documented
suppression of that exact prompt. So what was seen is the key **leaking**, which happened in two probes
on iOS 26.5.x, July and August. On 2026-09-21 the same stimulus on iOS 26.6.2 produced **no** prompt,
with more reads and kills than the original (`changes/archive/2026-09-21-album-gathers-retroactively`, design, task 6.2).

The spec now contradicts itself. *"The app owns the limited-library picker"* SHALLs the suppression, with
the scenario *"the automatic alert is not presented by the app's reads"*. *"No autonomous library reads
under a limited grant"* carries the scenario *"A photo taken elsewhere … exactly one limited-access alert
is queued"*. A third requirement still calls off-flow fetches *"the measured storm"*, a July claim the
August probe already superseded. An agent that reads this as fact designs around a prompt that current
iOS does not show.

## What Changes

- **Restate the prompt for what it is.** iOS's automatic "Select More Photos… / Keep Current Selection"
  prompt nudges the member to add photos to a partial selection. It does not guard reads: the app only
  reads the selection, and it suppresses the nudge with the Info.plist key.
- **Remove the unconditional "one prompt per photo taken" residual** and its scenario. Replace them with
  the measured history per release: the key leaked on 26.5 and 26.5.2, and held on 26.6.2. Nothing SHALL
  be designed on either outcome.
- **Keep what both measurements agree on as requirements:** reads of an unchanged library, and the app's
  own creations (imports, album creation and adds), raise no prompt.
- **Retire the leftover "measured storm" justification** from the candidate-source requirement. The
  fetch discipline it guards stays, justified by scope and round-trips, as the reads requirement already
  says.
- **Rewrite `CLAUDE.md` ①** to match. Update the capability's `## Purpose` at sync.
- **No behaviour changes.** The read discipline, the suppression key and the picker route stay exactly
  as they are. This corrects what the contract claims about the platform. It does not change what the
  app does.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `limited-photo-access`: the alert rule in *"No autonomous library reads under a limited grant"* (its
  residual and its "costs one prompt" scenario), and the storm justification in *"The limited selection
  is a facts-only candidate source for the admitted set"*. The Purpose paragraph is updated at sync.

## Impact

- `openspec/specs/limited-photo-access/spec.md`, through the delta and the sync-time Purpose edit.
- `CLAUDE.md` rule ①.
- No code, no build, no test, and no user-visible change. `internal` changelog label.
- Independent of phase 6 (`album-gathers-retroactively`), whose design only cites the measurement. It can
  ship alone.
