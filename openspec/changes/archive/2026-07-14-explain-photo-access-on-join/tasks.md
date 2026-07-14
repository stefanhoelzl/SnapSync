## 1. Upload arm: no membership, no arm

Do this first. `join-event` already requires that no upload producer is enabled until the user confirms,
and the explainer grants permission *before* the confirm — so this must land before the explainer can be
correct (design.md, Decision 7).

- [x] 1.1 Change `UploadArm`'s seam from `includesUpload: () -> Boolean` to `membershipIncludesUpload: () -> Boolean?`, where `null` means no event is configured. Document the three values on the parameter.
- [x] 1.2 `onPermissionGranted()`: start only when `membershipIncludesUpload() == true`, so a grant with no membership fires **neither** verb. `onProvision()`: same `== true` comparison (its `else stop()` is unaffected — a provision always has a membership). Leave `onLeave()` untouched.
- [x] 1.3 Add `commonTest` cases in `:capability:upload` (JVM **and** `iosSimulatorArm64`): a transition to `GRANTED` with no membership calls neither verb; a grant with an upload-direction membership still calls `start()`; a grant with a download-only membership still calls `stop()`; provisioning after a membership-less grant calls `start()` (the producer is armed at the join, not at the grant).
- [x] 1.4 In `:app:ios`, delete `SnapSyncRoot.uploadArmEnabled()` and wire the arm to the pure projection `{ config.config.value?.direction?.includesUpload }` — no `?:` left in the root. Confirm no other caller of `uploadArmEnabled()` remains.
- [x] 1.5 Run `./gradlew build` and `./gradlew compileIosMainKotlinMetadata`.

## 2. The AppExplainer component

- [x] 2.1 Add `AppExplainer(headline: String, paragraphs: List<String>)` to `:domain:ui:components`: the neutral `StatusIndicator.Photos` glyph (already defined, currently unused), the headline, and the paragraphs with component-owned spacing. No buttons, no `Modifier`, no color/shape/text-style param, no Material 3 type in the signature.
- [x] 2.2 Verify the signature against the `design-system` spec: semantic-only, and the component — not the caller — owns paragraph arrangement.

## 3. The join phase

- [x] 3.1 Add `JoinPhase.ExplainAccess(name: String, defaultCutoff: String)` to `:domain:presentation`. KDoc it as the pre-dialog explainer that carries `name`/`defaultCutoff` solely to hand off to `Ready` — it renders neither.
- [x] 3.2 Make `StatusContainerHost`'s `permissionSource` a `private val` so `loadInto()` can read `.value`.
- [x] 3.3 In `loadInto()`, on a `Found` result, choose `ExplainAccess` over `Ready` exactly when `configSource.config.value == null` (a first join, never a switch) **and** `permissionSource.permission.value == NOT_DETERMINED`. A snapshot read, not an observation.
- [x] 3.4 Add the `onAcknowledgeAccess()` intent: call `requester.request()` and swap the phase to `Ready(name, defaultCutoff)` in the same intent. Do not await the permission outcome — `request()` cannot suspend, and the dialog lands over the confirm surface.
- [x] 3.5 Extend the private `JoinPhase.name()` helper to carry `ExplainAccess`.
- [x] 3.6 Confirm cancel needs no new intent: `onCancelJoin()` already clears the pending join from any phase.

## 4. The screen

- [x] 4.1 Add the `ExplainAccess` branch to `JoiningEventScreen`'s body: `AppExplainer` with the headline "Photo access" and the three paragraphs from design.md, Decision 3 — share-first, direction-neutral, no event name, no "cutoff"/"upload"/"backup".
- [x] 4.2 Add the matching bottom action cluster: `PrimaryButton("I understand", onAcknowledgeAccess)` + `SecondaryButton("Cancel", onCancel)`, in the same cluster that already holds Join/Cancel and Retry/Cancel.
- [x] 4.3 Thread `onAcknowledgeAccess` from `StatusScreen`'s parameters down to `JoiningEventScreen`, and bind it in both hosts (`MainViewController`, the desktop `StatusPane`) as a method reference — no host logic.
- [x] 4.4 Add the unreachable `is JoinPhase.ExplainAccess -> Unit` branch to `SwitchDialog`'s exhaustive `when`, with a comment naming the invariant: a switch has `config != null`, so `loadInto` cannot emit it.
- [x] 4.5 Confirm `JoinedLayer`, `SyncHealth`, `AccessPrompt`, and the amber pill are untouched — the pill keeps calling `request()` directly (design.md, Decision 5).

## 5. Tests

- [x] 5.0 **Added in flight (approved):** fix the pre-existing cutoff-seed bug the explainer sits on. The row was seeded at first composition — which is `JoinPhase.Loading`, before any default exists — so it silently defaulted to *now* instead of the event's `createdAt` for every real join. Now seeded once from the first phase carrying a default. Pinned by a regression test driving the real `Loading → ExplainAccess → Ready` sequence, plus a `join-event` spec requirement.

- [x] 5.1 `StatusContainerHostTest` (`commonTest` — JVM **and** `iosSimulatorArm64`): a first join with `NOT_DETERMINED` reduces to `ExplainAccess` and has made no permission request; `GRANTED` goes straight to `Ready`; `DENIED` goes straight to `Ready` with no request; a **switch** with `NOT_DETERMINED` goes to `Ready` and never explains (this is what keeps 4.4's branch dead); `onAcknowledgeAccess()` calls `request()` exactly once and advances to `Ready` carrying the same name and cutoff; cancelling from `ExplainAccess` enrolls nothing and saves no config.
- [x] 5.2 `JoinScreenTest` (Compose-desktop JVM, headless): the `ExplainAccess` phase renders the access copy and both buttons; "I understand" invokes `onAcknowledgeAccess`; "Cancel" invokes `onCancelJoin`.
- [x] 5.3 Confirm the existing 31 UI tests and the `event-creation-ui` create-path tests still pass — a created event now routes through the explainer when permission is `NOT_DETERMINED`.

## 6. Harness

- [x] 6.1 ~~Add an `ExplainAccess` preset to the forge harness~~ — **dropped, false premise.** The forge harness (`:app:desktop:ui`) never passes `loadJoinDetails` (it takes `StatusPane`'s `{ JoinLoad.Failed }` default) and has no scan affordance, so the join gate is unreachable there *entirely* — it cannot forge `Ready`, `NotFound`, or any other join phase either. That is a pre-existing gap (the join gate postdates the forge harness), not something this change introduces, and closing it means building join-gate forging as a harness feature — out of scope here. Filed as a follow-up below.
- [x] 6.2 Reviewed the real `ExplainAccess` composable by rendering `:domain:ui` offscreen (the same headless Compose-desktop renderer the UI tests use) and inspecting the output: neutral photo glyph, headline, three paragraphs, `I understand` / `Cancel` in the standard bottom cluster, event unnamed.
- [x] 6.3 **Operator step (needs a display):** drive the real stack with `./gradlew :app:desktop:run`, set the permission preset to `NOT_DETERMINED`, and join an event — the full-stack harness already wires the real `loadJoinDetails`, so the explainer emerges from the real container with no harness change.

**Follow-up (not this change):** the forge harness cannot reach the join gate at all. Give `:app:desktop:ui` a `loadJoinDetails` stub + a "scan a QR" action so every `JoinPhase` — including `ExplainAccess` — is forgeable, as `desktop-test-harness` ("forges any display state") implies it should be.

## 7. Specs and close-out

- [x] 7.1 Run `npx --yes @fission-ai/openspec@1.5.0 validate explain-photo-access-on-join --strict`.
- [x] 7.2 Run `./gradlew build` and `./gradlew compileIosMainKotlinMetadata` — the full check plus the Linux-runnable iOS proxy.
- [x] 7.3 Write the deltas into `openspec/specs/` via `/opsx:archive` (CLI 1.5.0 has no `sync` subcommand — `archive` is what updates the main specs), then `validate --specs --strict`. Deltas: `join-event` +explainer +cutoff-seed; `design-system` +`AppExplainer`; `sync-status-screen` −the two dead `PermissionBlocked` requirements; `upload-lifecycle` ~the no-membership rows and scenarios.
- [ ] 7.4 Branch → PR → `/ship`.
