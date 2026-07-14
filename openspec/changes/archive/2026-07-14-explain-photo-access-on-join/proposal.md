## Why

Joining an event today asks for nothing and explains nothing: a scanned QR (or a freshly created
event) walks straight through the join gate, enrolls the device, provisions the config, and lands on
the joined screen — where an amber "Allow photo access" pill is the first and only hint that this app
is about to read the camera roll. The system dialog, when it finally fires, arrives with no statement
of what the grant is *for*.

That is the wrong order for this app. SnapSync's inherited danger — the one CLAUDE.md opens with — is
that "back up everything of mine" becomes "upload a guest's whole camera roll to a stranger's event".
The moment a person is handed the photo-library dialog is the moment they deserve to have been told,
in plain words, that photos they take will be shared automatically with everyone in the event. An
explainer screen shown before the first system dialog is the consent step the join flow never had.

## What Changes

- **A new join phase, `JoinPhase.ExplainAccess`.** When the join gate's details fetch succeeds on a
  **first** join (`config == null`) and photo permission is `NOT_DETERMINED`, the gate shows a
  full-screen explainer instead of going straight to the confirm surface. "I understand" fires
  `PermissionRequester.request()` — the only way in the interactive flow to reach the system dialog —
  and advances to the existing confirm phase. "Cancel" reuses today's `onCancelJoin()`, aborting the
  join with nothing enrolled and no config saved.
- **The explainer covers creators too, for free.** A created event routes through
  `onEventCreated → startPending`, the same gate a scanned QR opens, so no separate create-path work is
  needed.
- **`DENIED` and `GRANTED` skip it.** iOS shows the photo dialog exactly once; from `DENIED`,
  `request()` is a silent no-op. Explaining and then producing no dialog would be a lie, so the
  explainer renders only for `NOT_DETERMINED`. A `DENIED` joiner lands on the joined screen with
  today's "Turn on full access in Settings" pill.
- **The switch path and the amber pill are unchanged.** A switch confirmation (`PendingSwitch`) never
  produces the explainer; the joined screen's pill keeps calling `request()` directly. Both are
  reachable only by someone who has already been through a join.
- **A new `AppExplainer` design-system component**, built on the neutral `StatusIndicator.Photos`
  glyph that already exists for exactly this purpose and is currently unused.
- **The upload arm stops arming a producer for no event.** The explainer creates a state the system has
  never routinely seen — photo access `GRANTED` while `config` is still `null` — and `SnapSyncRoot`'s
  `uploadArmEnabled()` currently answers `true` there (`?: true`), so a grant at the explainer starts
  the producer **before the user has confirmed the join**. That directly violates a requirement
  `join-event` already carries: *"no config is saved and **no upload producer is enabled** until the
  user confirms."* This is not a tidy-up — the explainer cannot ship correctly without it. It is also
  already a contract violation on its own terms: `upload-lifecycle` says the arm is enabled "exactly
  when photo access is `GRANTED` **and** *the configured membership's* direction includes upload", and
  with no membership there is no direction, so the `?: true` is a divergence between the untested
  composition root and the spec. The decision moves into the tested `UploadArm`, where the spec always
  placed it, and the producer is armed at **provision** — the only moment there is an event to upload
  to.
- **Two dead requirements are deleted from `sync-status-screen`.** It still describes a full-screen
  `PermissionBlocked` gate (hero + "Allow access" button) that was removed in
  `2026-06-27-permission-on-status-screen` and does not exist in code. Leaving them while adding a
  third permission surface would leave the repo describing three, two of them fiction. Their two live
  rules — CTA-only priming, and copy that never frames the app as "backup" — are re-homed onto the new
  explainer requirement.

No breaking changes.

## Capabilities

### New Capabilities

None. The explainer is a phase of the existing join gate, which `join-event` already describes as "a
distinct, extensible `UiState` family … because joining is where a member's participation is
configured, and those options were always going to accumulate."

### Modified Capabilities

- `join-event`: adds the requirement that the gate explains photo access before the first system
  dialog — the new phase, its gating (`first join` **and** `NOT_DETERMINED`), CTA-only priming, and the
  copy rules. Establishes that the switch path never explains.
- `design-system`: adds `AppExplainer(headline, paragraphs)` to the component inventory.
- `sync-status-screen`: **removes** the two stale requirements describing the deleted `PermissionBlocked`
  gate.
- `upload-lifecycle`: adds one scenario pinning the previously-implicit case — a transition to
  `GRANTED` with no event configured calls neither producer verb. No existing requirement changes; the
  scenario makes explicit what the enabled-condition already implied.

`permission-gate` is **not** modified: the three-state model, both ports, the `.limited → DENIED` rule,
and the Settings route are all untouched. The explainer is a new caller of `request()`, not a change to
it.

## Impact

| Module | Change |
| --- | --- |
| `:domain:ui:components` | New `AppExplainer(headline: String, paragraphs: List<String>)` — glyph + headline + spaced body, no buttons, no appearance params. |
| `:domain:presentation` | New `JoinPhase.ExplainAccess(name, defaultCutoff)`; `permissionSource` becomes a `private val` so `loadInto()` can read `.value`; the gating in `loadInto()`; new `onAcknowledgeAccess()` intent; `JoinPhase.name()` extended. |
| `:domain:ui` | New `ExplainAccess` branch in `JoiningEventScreen` (body + bottom action cluster). `SwitchDialog` gains an unreachable branch, documented. `JoinedLayer` untouched. |
| `:capability:upload` | `UploadArm`'s seam becomes `membershipIncludesUpload: () -> Boolean?` (`null` = no event joined); `onPermissionGranted()` and `onProvision()` compare `== true`. `onLeave()` untouched. |
| `:app:ios` | `uploadArmEnabled()` deleted; the arm is wired to a pure projection with no `?:`. Wiring-only, as required. |
| `:app:desktop:ui` | A forge preset for the `ExplainAccess` phase, so the screen is reviewable on desktop without a device. |
| Tests | `StatusContainerHostTest` and new `UploadArm` cases in `commonTest` (JVM **and** `iosSimulatorArm64`); `JoinScreenTest` render/tap cases in the Compose-desktop JVM suite. |

No backend, no persistence, no schema, and no dependency changes. Nothing about the upload path, the
ledger, the discovery cursor, or the capture-date cutoff is touched.
