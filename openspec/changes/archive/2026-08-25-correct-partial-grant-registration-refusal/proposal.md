## Why

Under a partial (`.limited`) photo grant, iOS refuses `setUploadJobExtensionEnabled` in **both**
directions with `PHPhotosErrorAccessUserDenied` (3311). `registrationOutcome` classifies that as
`RegistrationOutcome.Failed` at `Error` severity, and `crash-reporting` turns every `Error` line into an
event — so **every member who switches Photos to Limited Access raises a crash report**, on a supported
user action, in a capability whose premise is that a partial grant is a first-class grant. It fires once
per membership-lifecycle action while limited (the grant flip, each join/switch/create, each leave), not
once per member, and it buries the registration failures that reporting exists to surface. The
`PHPhotosErrorIdentifierNotFound` (3201) carve-out already exists for exactly this shape.

The same measurement falsifies a claim standing in **three** live places: `ios-photokit-upload`,
`limited-photo-access`, and `CLAUDE.md` all state that under `.limited` *"registration succeeds and
lies"*. It does not succeed — it is refused, with an error, which nobody read because the call site
discarded its `Boolean` and `NSError` until the classifier landed (`2026-08-24-retire-launch-env-triggers`).
`ios-photokit-upload` additionally carries a scenario asserting a deregistration the platform refuses.

## What Changes

- **`PHPhotosErrorAccessUserDenied` (3311) on a *disable* becomes an expected outcome**, reported at
  `Warn` — a breadcrumb, never an event. The record survives the refusal and is **inert**: the OS does
  not invoke the extension under a partial grant, and a return to a full grant re-registers regardless,
  so no state is left that two writers could contend for.
- **3311 on an *enable* stays at `Error`**, but gains its own outcome naming the cause instead of falling
  into the generic failure. That case is invisible and terminal — nothing is registered, no cycle runs,
  and the screen sits at "Synchronization pending…" — and it is reachable only under a development
  mechanism override, never in a shipped build.
- **The falsified mechanism is corrected wherever it is asserted.** *"Registration succeeds and lies"* is
  replaced by what was measured: a partially-granted process cannot change its upload-job registration at
  all. The conclusion every consumer depends on is unchanged and better founded — not "the OS declines to
  invoke a registered extension" but "the app cannot register one".
- **The false scenario is replaced.** `ios-photokit-upload`'s *"Switching to limited deregisters the
  extension"* asserts an outcome the platform refuses; it is replaced with the measured behaviour.
- **`limited-photo-access`'s deregistration effect is qualified.** Its resolved-mechanism requirement says
  the producer "deregisters the extension before pumping"; under a partial grant that attempt is refused
  and the record survives, inert. The requirement's purpose — removing the dependence on a lifecycle
  transition firing — is unaffected.

Not in scope: skipping the post-disable ledger repair when the disable was refused. `deregister()` (from
`collapse-upload-tier-seam`) already takes the tier-switch path, where running the repair was the actual
hazard; the only remaining caller is the leave path, where `ios-photokit-upload` requires the clear on its
own terms.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `ios-photokit-upload`: the reporting requirement's enumeration of expected registration errors widens
  from one measured code to two, and gains the named enable-refusal; the limited-grant requirement is
  renamed to lead with the cause, its false scenario is replaced, and its mechanism is corrected from
  "registration succeeds" to "the registration cannot be changed".
- `limited-photo-access`: the second of the three measured platform facts in its Purpose is corrected, and
  the resolved-mechanism requirement's claimed deregistration effect is qualified.

## Impact

- `domain/src/commonMain/kotlin/app/snapsync/model/RegistrationOutcome.kt` — two new sealed members and
  two classifier branches; a named constant for 3311 beside the existing 3201 one.
- `domain/src/commonTest/kotlin/app/snapsync/model/RegistrationOutcomeTest.kt` — severity and message
  assertions for both new outcomes, and for the asymmetry between them.
- `CLAUDE.md` — the in-context copy of the same measured fact. Outside `openspec/`, but it is what every
  agent in this repository reads first, and it is the half of the digest no guard keeps in sync
  (`LawsDigestTest` scopes to the `## The laws (digest)` section only).
- No behaviour change. No adapter, shell, flow, or composition change. `PhotoKitUploadProducer` already
  renders whatever the classifier returns without branching.
- No new dependency, and no guard is implicated: nothing pins `PHPhotosError` constants, and
  `PlatformVocabularyPinTest` covers Apple enumerations decoded with a fallback arm — here the fallback
  arm is the loud answer, so a widened vocabulary cannot hide.
