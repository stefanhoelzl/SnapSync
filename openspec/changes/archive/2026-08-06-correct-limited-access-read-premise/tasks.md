# Tasks

This change corrects a **recorded belief**, not behaviour. No production code changes. The work is
making three documents agree with a measurement, and making the residual visible.

## 1. Correct the in-context brief first

- [x] 1.1 `CLAUDE.md` fact ① — it states the superseded rule ("off-flow reads queue … an app-killing
      storm") and is injected into every agent in every worktree, so it misleads continuously. Replace
      with the measured rule: the alert is armed **once per out-of-scope library change** and surfaced
      by the next fetch; app-created assets never arm it; read volume does not change the count.
- [x] 1.2 In the same edit, keep the *operational* guidance that remains true — reads still happen only
      at the cold-launch baseline and observer emissions — but stop attributing it to alert suppression.
- [x] 1.3 Say plainly, where a reader will see it, that a partial grant costs **one system prompt per
      photo the member takes**, and that no read strategy avoids it.

Done first and separately because it is cheap, it is the only artifact actively costing other people
time, and it does not depend on anything below.

## 2. Land the spec correction

- [x] 2.1 Author `specs/limited-photo-access/spec.md` — the MODIFIED "No autonomous library reads under a
      limited grant": behaviour unchanged, justification corrected, residual stated, expiry trigger and
      evidence caveats inline. **The main spec's requirement text still reads the old way on purpose** —
      deltas land at `/opsx:sync` or archive. Do not hand-apply it; that would conflict with the sync.
      (The Purpose was edited directly in 2.2 because prose outside a requirement has no delta form.)
- [x] 2.2 Update the spec's **Purpose**, whose first "measured platform fact" is the contradicted claim.
      Purpose text is not requirement-shaped, so it is edited directly rather than via a delta.
- [x] 2.3 Point the Purpose's decision-record line at **both** records: the original
      `2026-07-20-accept-limited-photo-access` and this change, in that order, so the history reads as
      supersession rather than replacement.
- [x] 2.4 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` stays green (62/62).

## 3. Leave the archive alone, deliberately

- [x] 3.1 Do **not** edit `changes/archive/2026-07-20-accept-limited-photo-access/PROBE-FINDINGS.md`.
      It is the record of what was believed and why; superseding it is this change's job, rewriting it
      would destroy the evidence that the belief was ever held. (Verified: archive has no diff.)
- [x] 3.2 Add one line at the top of this change's `PROBE-FINDINGS.md` naming which section it
      supersedes (§5, "the plist key does not reliably suppress the alert"), so a reader arriving at
      either document finds the other.

## 4. Verify nothing depended on the wrong reason

- [x] 4.1 Grep for other places repeating the superseded rule. **Four** code sites, not the two this
      task predicted (`SelectionScopedTransfer` turned out not to cite it): `PermissionAwareCandidateSource`,
      `PhotoKitCandidateSource` (×2), `UrlSessionUploadController`. Citations corrected to the reason that
      survives — under a partial grant the selection **is** the scope — with the alert rule noted as *not*
      the argument. **No code changed.**
- [x] 4.2 Confirm no guard or test asserts the superseded justification. One test comment did
      (`PermissionAwareCandidateSourceTest`) — corrected, assertions untouched. No `:test:architecture`
      guard encodes it.
- [x] 4.3 **`diagnostic-logging`** — discovered during 4.1: a normative `SHALL NOT` (the dump omits the
      partial-selection size) rested on the superseded reason, in a *second* capability's spec. Added a
      `diagnostic-logging` delta re-justifying it on that requirement's **own** existing principle —
      *"the dump SHALL read no data the app does not already read … and SHALL add no port surface for
      diagnostics alone"* — which excludes the count regardless of alerts, since the selection snapshot
      lives in `compose/` and this feature has no shipped read of it. Behaviour unchanged; one scenario
      added. `CollectDiagnosticDump` KDoc and its test comment corrected to match.
- [x] 4.4 **Deliberately left alone:** `limited-photo-access`'s picker requirement still says *"without
      suppression the autonomous-era alert storm was app-killing"*. That describes the regime **without**
      `PHPhotoLibraryPreventAutomaticLimitedAccessAlert`, which this probe never tested — every run
      carried the key. Correcting it would be asserting something unmeasured. Recorded here so the
      omission reads as a decision rather than an oversight.

## Explicitly not in this change

- **Removing or reshaping the read discipline.** It is kept. Whether it should exist at all now that its
  stated forcing proof does not force is a real question, and a separate one.
- **Fixing the residual.** One prompt per photo taken is armed by the OS on a change the app never
  observes, so no read strategy avoids it. Whether `LIMITED` is a supported grant for *contributing*
  members is a product decision, not a spec correction.
- **Re-measuring.** The evidence is one device, one OS point release, **n = 1** out-of-scope change.
  Stated as such in both the findings and the spec; widening it is worth doing before anything is built
  on the rule, and worth nothing before then.
