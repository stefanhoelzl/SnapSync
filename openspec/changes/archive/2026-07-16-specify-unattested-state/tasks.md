## 1. Establish that nothing was lost, only never written

The instinct on finding an unowned state is to hunt for a dropped delta. There isn't one, and saying so
matters: it is the difference between "the archive misfired" (fix the tool) and "the scope list was decided
before implementation and never revisited" (fix the process).

- [x] 1.1 Record the provenance: `git log -S "Unattested"` over `:domain:presentation` + `:domain:ui`
      returns exactly **one** commit, `1f85ce6`, which created the change directory **and** the
      implementation in one squash. `git show --name-only 1f85ce6` lists its nine `specs/` deltas — every
      one backend — alongside `UiState.kt`, `StatusContainerHost.kt`, `AttestedSource.kt`, and
      `AppStatusLine.kt`. The archive commit synced faithfully what it was given. **No delta to recover.**
- [x] 1.2 Record the cause, because it is the same one this repo keeps hitting: that change's **D11**
      promised *"no new screen, no new `App*` component"*, so `sync-status-screen` and `design-system` were
      never on its Modified-Capabilities list — and nothing prompted a delta when the code touched them
      anyway. D11 was falsified during implementation; the correction went into **`tasks.md` 4.5**, an
      implementation log. D11 was never amended, and its neighbour D10b *uses* the state D11 denies exists.

## 2. Give the state an owner

- [x] 2.1 `sync-status-screen` — "Sync status snapshots reduce to UI state": insert `Unattested` as the
      **fourth** rung (below `NotStarted`, above the snapshot values) and carry its rationale from the code
      and `tasks.md` 4.5: raised only when no usable token could be obtained, never for a merely stale one;
      not actionable; self-clearing, because opening the app **is** a wake.
- [x] 2.2 Same spec — amend "the **sole** attention state" to the sole **actionable** one (D2). Do not
      delete the clause: `NeedsAccess` really is the only state that asks the member for something, and
      that is the distinction the code renders on (tappable + chevron vs neither).
- [x] 2.3 Same spec — "Joined-layer health descriptor and status line" enumerates the status-line states and
      repeats "the only status-line state that carries a background". Add `Unattested` there too. **This
      requirement was not in the original finding**; it surfaced only when the delta was built from the main
      spec instead of from the audit's line numbers.
- [x] 2.4 `design-system` — add `CannotVerifyDevice` to the sealed-value list; amend "the **only** variant
      carrying a background" to the two attention variants, keeping the tappable/chevron distinction. State
      that it takes **no** `onClick`, so un-tappability is structural rather than a call-site convention.
- [x] 2.5 `device-attestation` — correct the false requirement. It said the failure "SHALL be reduced into
      the **existing** visible error state"; it is a new health value and a new component. Keep its real
      obligation (**visible, never silent** — that is attestation's failure to surface) and cross-reference
      `sync-status-screen` for which state renders it. Add the scenario that a **stale-but-renewable** token
      surfaces nothing, which the old text never said and the code has always done.
- [x] 2.6 `desktop-test-harness` — re-point its citation from `device-attestation` to `sync-status-screen`.
      It was the only spec that named `Unattested` and it pointed at a spec that never claimed it.

## 3. Verify the deltas are diffs, not rewrites

The previous change (`fix-stop-prohibition-scope`) nearly deleted a requirement's worth of contract by
restating it from the parts that had been read. An in-place `MODIFIED` is a whole-requirement rewrite, and
the whole is the hazard.

- [x] 3.1 Build **every** delta requirement programmatically **from the current main spec**, then apply only
      the intended replacements. Never retype a requirement.
- [x] 3.2 Diff each delta against its main requirement and assert the removed lines are **only** the ones
      intended. For `sync-status-screen`'s precedence: exactly **2** removed (the "sole attention state"
      phrasing), 17 added. Any other removal is contract being deleted silently.
- [x] 3.4 **The sync caught a gap the delta did not**: `design-system`'s scenario "Only the attention state
      has a background" was outside every requirement I modified, so it matched main byte-for-byte and
      passed every check above — while its requirement now says two states carry one. A delta verified only
      against the requirements it names cannot see a scenario elsewhere that contradicts them. Split into
      two scenarios: the flat states, and the two attention states distinguished by chevron + tap.
- [x] 3.3 Confirm `design-system`'s **reduced-motion** clause survives the edit. It is unimplemented and
      therefore tempting to drop while the paragraph is open — but the spec is right and the code is wrong,
      so dropping it would make the contract worse to make it true. It is a code defect, not this change's.

## 4. Verify

- [x] 4.1 `openspec validate --specs --strict` and `openspec validate specify-unattested-state --strict`.
- [x] 4.2 `./gradlew build` — expected to be a no-op. **No code changes**: if anything compiles differently,
      this change has exceeded its scope.
- [x] 4.3 Confirm the tree agrees with itself: `grep -rn "Unattested\|CannotVerifyDevice"` over
      `openspec/specs/` should show `sync-status-screen` specifying it, `design-system` rendering it,
      `device-attestation` and `desktop-test-harness` pointing at `sync-status-screen`, and no spec claiming
      `NeedsAccess` is the only attention state or the only backgrounded variant.

## 5. Hand off

- [x] 5.1 Record that this unblocks two corrections the drift sweep deliberately skipped
      (`design-system`'s "only variant with a background", `sync-status-screen`'s three rungs) — both were
      identified as false and both left, because fixing the sentence without an owner would only relocate
      the lie.
- [x] 5.2 Record what remains: `design-system`'s reduced-motion requirement is unimplemented, and the forge
      harness's `attestedSource` defaults to `AlwaysAttested`, so the `!attested` branch is exercised only
      by the harness's own preset — there is no test that the **real** attestation path raises it.
