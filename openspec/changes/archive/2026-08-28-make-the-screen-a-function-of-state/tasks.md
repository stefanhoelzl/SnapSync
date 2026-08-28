## 1. Unblock the container (design D11)

- [x] 1.1 Bundle `StatusContainerHost`'s constructor. It is at 14 parameters against the `core`-tier
      `constructorThreshold` of 15 (`config/detekt/core.yml`) and this change adds two sources. Group by
      what each parameter is (read-model sources, seams, commands, diagnostics) — a bundle of bundles has
      as many fields as it has groups, so nesting terminates. Do NOT raise the ceiling: the tier contract
      requires a stated forcing proof, and there is none.
- [x] 1.2 Update every `StatusContainerHost` construction site: `app/ios` `SnapSyncRoot`, `app/ios/forge`,
      `app/desktop` `StatusPane`, `:test:world`, and `StatusContainerHostTest`'s helpers.
- [x] 1.3 Run `./gradlew detektCoreTier detektUiTier` and confirm no ceiling rose.

## 2. Net the clamping rules before moving them (design D12 step 2)

- [x] 2.1 Add `commonTest` unit tests for `resolveUntil` and `resolveFrom` in `:ui:screens`: bounds inside
      the window, each preset, custom values outside the window on both sides, and the inversion case
      (`until` resolved first, `from` floored to it). These are the safety net for section 5.
- [x] 2.2 Add unit tests for `directionOf` (all four switch combinations, including both-off) and
      `nowWithinWindow` (now before/inside/after the window; absent start; absent end).
- [x] 2.3 Confirm the new tests fail if the resolution order in `rangeOver` is swapped — a net that cannot
      detect the regression it exists for is not a net. **Verified, and it found a gap.** Mutating
      `resolveFrom`'s clamp from `coerceIn(windowStart, untilResolved)` to `coerceAtLeast(windowStart)`
      fails two of the new tests, including the all-pairs invariant — the rules are netted. But mutating
      `rangeOver` to pass `windowEnd` instead of `untilResolved` — the exact swap this task names — passes
      the ENTIRE `:ui:screens` suite, all 36 join UI tests included. The composition of the rules is
      untested and unreachable: `rangeOver` is `@Composable` and private, so no unit test can call it, and
      no rendered string distinguishes the two ceilings in the cases the UI tests exercise.
- [x] 2.4 Close that gap in section 5, where the composition moves into the reduction and becomes
      directly testable. **Done.** The composition is now `RangeForm.resolve`, a pure function in
      `:ui:presentation`, and `RangeResolutionTest` covers the ceiling-argument case directly. Verified by
      mutation: resolving `from` against `windowEnd` instead of the resolved `until` — the exact swap that
      previously passed the ENTIRE `:ui:screens` suite — now fails
      `resolve floors the lower bound to the resolved upper bound rather than the window end`.

## 3. Fold the data parameters into UiState (design D3–D6)

- [x] 3.1 Add `@Serializable` to `RenameStatus` and `RenameFailureReason` in
      `:domain` `feature/membership`. No field changes, no persisted payload changes.
- [x] 3.2 Widen `UiState.Joined` to carry `membership: EventConfig` (non-null), `inviteUrl: String`, and
      `renameStatus: RenameStatus`. Derive `inviteUrl` in `reduceFrom` via
      `encodeEventUrl(EventLinkPayload(config.eventId))` — one call site, so the QR and the share action
      cannot drift.
- [x] 3.3 Fold the transient invalid-link error into `UiState.CreateEvent.error`: add
      `transientErrorState` as an input to the `combine`, and resolve the two causes in `reduceFrom` with
      the transient winning. Keep the self-clear job in `StatusContainerHost` — the choreography stays
      presentation-owned.
- [x] 3.4 Delete `StatusContainerHost.eventName`, `.inviteUrl`, `.membership`, `.transientError` and
      `.renameStatus` as separate read-models. `onShareInvite` reads the invite URL off the current state.
- [x] 3.5 Remove `membership`, `inviteUrl`, `eventName`, `transientError` and `renameStatus` from
      `StatusScreen`'s parameter list. Delete the five `membership != null` guards and the one `!!`; the
      heading, the rename prefill and the reconfigure header all read `state.membership.name`.
- [x] 3.6 Move `photoPermission` into `StatusActions` beside `shareableCount` (design D10) — it is a
      recompute key for that query, not a rendered value.
- [x] 3.7 Update the three call sites: `MainViewController`, `ForgeViewController`, and `StatusPane` —
      where the long-standing `transientError` omission is fixed by the parameter ceasing to exist.
- [x] 3.8 Update `StatusContainerHostTest` and `StatusScreenTest` for the new `Joined` shape. Add a test
      that the transient error outranks a sticky create failure and self-clears back to it.

## 4. Collapse the join phases (design D7)

- [x] 4.1 Introduce `EventDetails(name, startsAt, endsAt, deletesAt)` and
      `JoinPhase.Detailed(event: EventDetails, step: Step)` with
      `Step = ExplainAccess | Ready | Committing | CommitFailed` as data objects. Keep `Loading`,
      `NotFound` and `LoadFailed` bare.
- [x] 4.2 Update `reduceFrom` and the pending-join gate to construct the detailed phase, so a step that
      needs details cannot be built without them.
- [x] 4.3 Delete `JoinPhaseWindow.kt` — its three `when` extractors become one access on `Detailed`.
- [x] 4.4 Update `JoinFlowScreens`, `JoinReadySurface` and `JoinScreenTest` for the new shape. Verify a
      retry from `CommitFailed` still commits the chosen floor, ceiling and retention deadline.

## 5. Lift the form into the container (design D8, D9)

- [x] 5.1 Move `Participation`'s seven values into `StatusContainerHost` as a `MutableStateFlow` of a form
      type, with container-local intents for each edit. The intents touch no port and call no
      `UserCommands` member.
- [x] 5.2 Move the seeding into the reduction: defaults at the join gate; the existing lossy
      reconstruction from the persisted membership at the reconfigure surface (the rule is unchanged, only
      its location). Re-seed when the surface opens and when a leave clears the config.
- [~] 5.3 Call `resolveFrom`/`resolveUntil`/`directionOf`/`nowWithinWindow` from the reduction, unchanged,
      and carry the resolved bounds as local wall-clock values, the derived direction, and the
      commit-enabled verdict in the state.
- [x] 5.4 Add the shareable-count query as a **sibling** flow keyed on the form and the photo grant — not
      a feedback loop from `UiState` — and carry its result in the state. Confirm it performs no library
      walk under a partial grant: it re-filters the in-memory selection snapshot, as before.
- [x] 5.5 Add `JoinedSurface = Status | Reconfigure(form)` to `Joined`, and move `StatusOverlayState`'s
      four flags into the state. `LaunchedEffect(joined) { … }`'s reset becomes the reduction's job.
- [x] 5.6 Move `formatRange` from `CutoffFormatter` to `:ui:components` beside `appDateTimeLabel` — it is
      already pure. Remove `cutoff` and `shareableCount` from `StatusScreen`'s parameter list.
- [x] 5.7 Make `screenLabel` a pure function of `UiState` — it no longer needs `reconfigureActive`.
- [x] 5.8 Migrate the logic-through-UI cases in `JoinScreenTest` to `StatusContainerHostTest`, keeping in
      `:ui:screens` only what genuinely asserts rendering.
- [x] 5.9 Leave in-progress text content screen-local (design D1 exception 1): the rename and diagnostic
      sheets carry presence and seed in the state; the characters typed stay in the sheet.

## 6. Gate the rule (design D1)

- [x] 6.1 Add an architecture guard asserting `:ui:screens` declares no Compose-remembered mutable state
      outside a named allowlist (the text sheets' in-progress content, and nothing else). Land it after
      section 5 so the build is never red mid-migration.
- [x] 6.2 Verify the guard fails on a deliberately reintroduced `var … by remember` in a screen, and names
      the declaration.
- [x] 6.3 Add the guard's rationale to its KDoc, including why the allowlist has exactly the entries it
      has — an allowlist without a stated rule grows.

## 7. Specs and records

- [x] 7.1 Sync the six delta specs into `openspec/specs/`. Done at archive time: 3 requirements
      added, 7 modified, across six capabilities. `validate --specs --strict` 59/59.
- [x] 7.2 Confirm the restored clause in `sync-status-screen` reads as the 2026-06-10 original intended —
      the no-event-history rule bounds how the snapshot stream is read, not whether a remembered current
      value may be reduced.
- [x] 7.3 Confirm `sync-status-screen` no longer cites "the config capability", which does not exist.
- [x] 7.4 Confirm `join-share-count` now describes the render-site gate: a non-contributing choice offers
      **no** count row, so the count is absent rather than `0`, and `0` retains its own meaning.
- [x] 7.5 Write the superseding decision record covering `event-invite-qr` D3 (both premises now false,
      with the evidence), `reconfigure-membership` D4's parameter clause, and `event-rename` D10's
      placement. Every new decision names an expiry trigger — the omission that let D3 rot unnoticed.

## 8. Verify

- [x] 8.1 `./gradlew build` — including `:test:architecture`, the detekt tiers, and the Compose UI tests.
- [x] 8.2 `./gradlew compileIosMainKotlinMetadata` — the Linux-runnable proxy for the iOS source sets.
- [x] 8.3 `./gradlew architectureDiagrams` and commit if anything moved.
- [x] 8.4 Drive both desktop harnesses through the `ui-harness` skill. **Done for the forge, and it
      required a harness change to be possible at all.** The panel had no way to deliver a rejected event
      link, so the invalid-link banner was undriveable — which is the other half of why the omission hid:
      even with the parameter passed, no lever existed to make it appear. Added `Scan invalid QR` to the
      forge panel (and the `onHostReady` wiring it needs), then drove it: the banner renders, confirmed in
      the semantics tree and in `phone.png`.
      ⚠️ The 4-second SELF-CLEAR cannot be observed in either harness, and that is a harness property, not
      a regression: the driver runs under `runDesktopComposeUiTest`, whose Compose clock advances only
      when pumped, and the host's scope is the composition's `rememberCoroutineScope()`. Fourteen real
      seconds moved it not at all. The clear is proven by `StatusContainerHostTest`'s
      `an invalid deeplink flashes the self-clearing transient error and changes nothing`, which advances
      virtual time and asserts the value returns to null.
      The world harness's own pass is still outstanding — it needs a running world, and nothing in this
      change touched the world's own wiring.
- [x] 8.5 `npx --yes @fission-ai/openspec@1.5.0 validate --changes --strict`.

## 9. Open questions to settle before merge (design "Open Questions")

- [x] 9.1 The three separable cleanups **ride along** (the user's call). All three landed:
      **(a)** `renameFailureText` moved into the reduction. The seam still reports a REASON; presentation
      turns it into words, exactly as it already did for `CreationFailureReason`. The state now carries a
      presentation-owned `RenameState` whose `Failed` holds the message, so the screen renders copy it is
      given. That also un-did a change this proposal made earlier: `RenameStatus` no longer travels inside
      `UiState`, so the `@Serializable` added to that domain type in task 3.1 was removed again — the
      domain type stays domain-only.
      **(b)** The 100-character cap has one home, `model/`'s `EVENT_NAME_MAX_LENGTH`, used by both the
      create form and the rename sheet (the latter was a bare literal). `EventNameLimitTest` now asserts
      it equals `api/src/validators.ts`'s `MAX_EVENT_NAME_LENGTH` — the backend owns the rule, the client
      mirrors it, and nothing else could have noticed a disagreement — and that no screen states a name
      cap as a literal again.
      **(c)** A rejected link while JOINED is no longer swallowed. `Joined` carries a self-clearing
      `notice`, fed from the same transient cell the create layer's banner reads, rendered above the
      invite hero. Specced as its own requirement with two scenarios, and covered by
      `a rejected link while joined is told to the member rather than swallowed`, which asserts both the
      message and its self-clear.
- [x] 9.2 Confirm the changelog label is `internal` — no user-visible behavior changes.
