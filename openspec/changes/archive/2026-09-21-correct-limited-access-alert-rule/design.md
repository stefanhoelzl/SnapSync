## Context

The limited-access alert has been measured three times on the SE2. Each probe replaced the previous
reading:

| probe | iOS | stimulus | prompts | reading it produced |
|---|---|---|---|---|
| `2026-07-20-accept-limited-photo-access` | 26.5 | key present: first-grant picker; camera photo + re-fetches | storms; queued, surviving kill | off-flow fetches queue alerts; the key does not reliably suppress them |
| `2026-08-06-correct-limited-access-read-premise` (P0/P0b) | 26.5.2 | ~15 reads, unchanged library | 0 | reads alone arm nothing, so the storm was mis-attributed |
| same (P0c) | 26.5.2 | 1 camera photo, then reads | **1**, despite the key | "armed by the library changing, once per change": every photo costs a prompt |
| `album-gathers-retroactively` task 6.2 | **26.6.2** | 1 camera photo, then 11 reads over 4 launch-and-kill cycles | **0** | the key suppresses it, as documented |

Every row ran with `PHPhotoLibraryPreventAutomaticLimitedAccessAlert = true` in the installed bundle.
(July also measured without the key: a prompt on nearly every library touch.) Apple documents that key
as turning off exactly this automatic prompt. **On 26.5.x it leaked in two probes, both times after
changes outside the selection. On 26.6.2 it did not.** The prompt itself is a nudge to widen a partial selection, not a gate on reads.

The August reading was promoted to a SHALL-level residual, with a scenario asserting exactly one prompt.
It was then copied into `CLAUDE.md` ① as a platform fact with absolute wording: "every photo the member
takes costs one system prompt". Meanwhile another requirement, *"The app owns the limited-library
picker"*, SHALLs the suppression, and its scenario asserts the prompt is not presented. The capability
contradicts itself, and the newest measurement sides with the suppression.

## Goals / Non-Goals

**Goals:**

- State only what the measurements agree on as requirements. State what they disagree on as history,
  with an explicit instruction not to design on either outcome.
- Remove the internal contradiction between the two requirements.
- Correct the copy in `CLAUDE.md` that every session loads.

**Non-Goals:**

- **No behaviour change.** The read discipline (a cold-launch baseline, observer-emission reads, and the
  snapshot-fed discovery), the Info.plist key and the in-app picker all stay exactly as they are.
- **No new measurement.** Settling the out-of-selection case needs a second device or OS release, and the
  change says so rather than guessing.
- Revisiting the July "without suppression the storm was app-killing" note in *"The app owns the
  limited-library picker"*. That describes behaviour with the key **absent**, which no later probe tested.

## Decisions

### D1. Demote the out-of-selection case from rule to recorded disagreement

The leak is seen twice on 26.5.x and absent once on 26.6.2. That suggests iOS changed, but one device
and one newer probe cannot carry a SHALL either way. The requirement states the history per release and
forbids designing on either outcome.

*Rejected:*

- **Flipping the rule to "the key suppresses it"** would repeat the original mistake in the other
  direction, promoting one probe on one release to a law.
- **Deleting the history** would lose the reason the read discipline was once framed as alert
  suppression, and invite the next agent to re-derive it.

### D2. Keep the read discipline, on the justification it already had

*"No autonomous library reads"* already says its behaviour stands for scope and round-trip reasons. The
candidate-source requirement still leaned on the "measured storm". This change points it at the same
justification, so no requirement argues from a retired premise.

### D3. Replace the prompt-per-photo scenario with one both probes support

The scenario *"A photo taken elsewhere costs one prompt"* is replaced by *"The app's own creations raise
no prompt"*: imports, and album creation and adds over selected assets. Both probes observed that, and
it is the property the app actually depends on. The event album and downloads rely on it.

## Risks / Trade-offs

- **[Members on iOS 26.5.x or earlier]** The key may leak for them, as it did twice on the SE2.
  → Nothing new is lost: the app never designed around the prompt beyond the key and the picker, and
  this change keeps both. The app's route to full access is unchanged.
- **[A related tension, left out of scope]** Album placement resolves assets with
  `PHAsset.fetchAssetsWithLocalIdentifiers`. That covers the enqueue-time placement, which runs in upload
  cycles and so off the sanctioned read points under a partial grant, and phase 6's gather, which user
  acts trigger. Both resolve assets already in the selection, which is closer to the permitted per-asset
  read than to a scope query. Nothing recorded measures a prompt from it. → Named here, not changed. If
  it matters, it belongs to a change about the fetch discipline's scope.

## Open Questions

- Which reading holds on the next iOS major. The expiry trigger in the requirement already says to
  re-measure there.
