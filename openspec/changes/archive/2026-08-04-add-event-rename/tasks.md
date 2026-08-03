## 1. Backend rename route

- [x] 1.1 Add `PATCH /events/:eventId` to `deviceApi` in `api/src/app.ts`: validate the UUID (`400`), parse JSON (`400`), reuse `validateEventName` (`400`), resolve through `gateEvent` (absent/incomplete → `404`, other read failure → `502`)
- [x] 1.2 Rewrite the marker with `name` replaced and **every other field verbatim** — no restamping of `createdAt`, `startsAt`, `endsAt`, `capacity`, or `lifetimeSeconds` — and respond `200` with `publicEvent(marker)`
- [x] 1.3 Comment the route with why `name` is the sole write-once exception and why verbatim rewriting makes the sweep race self-defusing, mirroring the create route's comment density
- [x] 1.4 Add `api/test/app.test.ts` cases: happy rename echoes the trimmed name; whitespace trimmed; every other marker field byte-identical after a rename; empty / whitespace-only / over-100 / absent name → `400` with no upstream write; non-JSON body → `400`; non-UUID id → `400`; absent event → `404`; marker read failure → `502`; wrong method on the path → `404`
- [x] 1.5 Verify the route is behind the existing device-token gate (a request without a valid token is refused, exactly as an ungated create is)

## 2. Domain port and HTTP adapter

- [x] 2.1 Add `EventRename` port + sealed `RenameOutcome { Renamed(name), InvalidName, Transient }` to `:domain` `ports/`, documenting why `404` collapses into `Transient` (the single-witness argument from design D6)
- [x] 2.2 Add `HttpEventRename` to `:adapter:generic:app`, modelled on `HttpEventCreation`: `PATCH <host>/events/<id>` with `{"name": …}`, `200` → `Renamed` from the echoed body, `400` → `InvalidName`, everything else / transport / parse → `Transient`, non-throwing
- [x] 2.3 Add `HttpEventRenameTest` (`commonTest`, MockEngine): request path/method/body shape; `200` echo parsed; `400` → `InvalidName`; `404` → `Transient`; `500` → `Transient`; transport failure → `Transient`

## 3. Rename use-case and status seam

- [x] 3.1 Add `RenameStatus { Idle, InFlight, Succeeded, Failed(reason) }`, `RenameFailureReason { INVALID_NAME, SERVER }`, `RenameStatusSource`, `MutableRenameStatusSource`, and a no-op renamer, in `:domain` `feature/membership` — mirroring the `CreationStatus` file, and documenting why `Succeeded` exists here when `CreationStatus` deliberately has no success value (design D10)
- [x] 3.2 Add `RenameEvent` to `feature/membership`: set `InFlight`, call the port, on `Renamed` read the config, **guard `eventId` still matches**, save the whole object with only `name` replaced from the **echoed** value, then set `Succeeded`; on `InvalidName`/`Transient` set `Failed(…)` and persist nothing. Document it as the fifth writer of the one-writer membership config
- [x] 3.3 Add the reset command returning the seam to `Idle`
- [x] 3.4 Add `RenameEventTest` (`commonTest`): successful rename saves exactly once with only `name` changed; the echoed name wins over the submitted one; a result for a non-matching `eventId` persists nothing; a rename with no config persists nothing; every failure persists nothing and leaves the config byte-identical; **no failure path clears the config, notifies a leave, or cancels downloads**; the status sequence is `InFlight → Succeeded` / `InFlight → Failed`

## 4. Composition and commands

- [x] 4.1 Add the rename command to the `UserCommands` bundle in `:domain` `model/`, keeping the existing no-op default so non-iOS hosts and tests construct unchanged
- [x] 4.2 Wire `RenameEvent`, the port, and the status seam in `compose/SnapSyncApp.kt`; build the live command there
- [x] 4.3 Build `HttpEventRename` in the iOS shell's adapter assembly over the shared Ktor client, alongside `HttpEventCreation`

## 5. Design-system components

- [x] 5.1 Add the optional `onEditHeading` callback to `ScreenLayout`: renders an edit control beside the heading with click semantics and an accessibility label; `null` renders nothing and leaves the layout unchanged
- [x] 5.2 Generalize `AppBugReportSheet` → `AppTextPromptSheet`: add an initial value, an optional error message rendered as an error banner above the actions (never as field styling), and a busy flag that keeps the sheet open, indicates the running action, and refuses confirm and every dismissal route
- [x] 5.3 Extend the confirm-disabled rule to "trimmed value is empty **or** equals the trimmed initial value", and confirm this is behaviour-identical for the bug report (whose initial value is empty)
- [x] 5.4 Update the bug-report call site in `StatusScreen` to the renamed component; confirm `DiagnosticDumpGestureTest` passes **unchanged**

## 6. Presentation and screen

- [x] 6.1 Expose `renameStatus` from `StatusContainerHost` as a screen-level value alongside `eventName` and `membership` — **no** new `UiState` family and no new branch in the reduction
- [x] 6.2 Wire the pen in `StatusScreen`: pass `onEditHeading` only in the `Joined` state, suppressed while a `pendingSwitch` is present and while the reconfigure surface is open
- [x] 6.3 Open `AppTextPromptSheet` pre-filled with the current name, capped at 100 characters, confirm disabled while trimmed-empty or unchanged; submit the trimmed value
- [x] 6.4 Drive the sheet from `RenameStatus`: busy while `InFlight`; close and fire the reset on `Succeeded`; on `Failed` keep it open with the typed value and show the reason in the error banner
- [x] 6.5 Add `StatusScreenTest` cases: the pen is present in every joined health value; absent during a pending switch; absent on the create screen, the join-gate phases, and the reconfigure surface; the pen exposes click semantics while the app-name label still exposes none; the dialog opens pre-filled; confirm disabled when empty and when unchanged; a failure renders a banner and keeps the sheet open with the typed value

## 7. World and integration tests

- [x] 7.1 Add the `PATCH /events/:id` route to `:test:world`'s mini-edge, with the same validation, `404`, and verbatim-rewrite behaviour as the real backend
- [x] 7.2 Add a `:test:integration` end-to-end test (`commonTest`): rename over the composed real core, asserting the world's marker carries the new name **and** the resulting screen-level event name / heading value reflects it
- [x] 7.3 Add an integration case proving a `404` rename leaves the membership joined and the config unchanged

## 8. Finish

- [x] 8.1 Run `./gradlew build` (compiles all targets, runs JVM tests including the architecture guards) and `./gradlew compileIosMainKotlinMetadata`
- [x] 8.2 Run `deno task test` (or the repo's equivalent) in `api/`
- [x] 8.3 Run `./gradlew architectureDiagrams` and commit any regenerated `architecture/` output — the diagrams check is required and stale output blocks the PR
- [x] 8.4 Run `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict`
- [x] 8.5 Optional device check: build a dev IPA via the ssh-mac loop, join a headless event, rename it, and confirm the marker changed and the heading followed
