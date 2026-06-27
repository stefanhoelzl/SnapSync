## 0. Spike: confirm qrose compiles for iOS (gates the rendering path)

- [x] 0.1 Added `qrose = "1.1.2"` + `io.github.alexzhirkevich:qrose` to `gradle/libs.versions.toml`
      and `implementation(libs.qrose)` to `:domain:ui:components`; dropped a throwaway
      `rememberQrCodePainter("snapsync://test")` composable in the components module.
- [x] 0.2 Ran `./gradlew compileIosMainKotlinMetadata` → **BUILD SUCCESSFUL**. qrose links for iOS
      under Kotlin 2.4.0 / Compose MP 1.11.1. **Library path confirmed**; the DI render-seam fallback
      is moot. Throwaway composable deleted; proceeding with section 2/3 as written.

## 1. Invite URL derivation (`:domain:presentation`)

- [x] 1.1 `StatusContainerHost` derives the invite deeplink from `ConfigSource`
      (`config → encodeConfigUrl`) and exposes it as `val inviteUrl: StateFlow<String?>`
      (`stateIn(scope, Eagerly, …)`, `null` when no config). Reuses the existing `:capability:config`
      dependency — no new module dependency, no `UiState` change.
- [x] 1.2 Added an injected `share: (String) -> Unit = {}` lambda (no-op default — the `leave`
      pattern) and `fun onShareInvite() = intent { inviteUrl.value?.let { share(it) } }`. `UiState` and
      the `reduceFrom` reduction **unchanged**.
- [x] 1.3 `commonTest`: configured event yields the invite URL that decodes back to the same `eventId`
      (and equals `encodeConfigUrl`); absent config yields `null`; `onShareInvite()` invokes `share`
      with that URL; no config → no share; default no-op constructs unchanged and is inert.

## 2. Design-system components (`:domain:ui:components`)

- [x] 2.1 Added `AppQrCode(content: String, caption: String? = null)` — renders a scannable QR
      (qrose `rememberQrCodePainter`) plus the optional caption beneath; no appearance/`Modifier`/M3
      params. The qrose import is **only** here.
- [x] 2.2 Added flat, icon-only `ShareButton(description, onClick)` mirroring `LeaveButton`
      (`Icons.Filled.IosShare`); no appearance/Material 3 in the signature.
- [x] 2.3 Evolved `ScreenLayout`'s bottom-right slot into a container-arranged **bottom-end action
      cluster** (`bottomEndActions: (@Composable () -> Unit)? = null`) that row-arranges the supplied
      actions end-aligned with `spacedBy(8.dp)`. Only caller is `StatusScreen` (updated in §3).

## 3. Status screen: invite affordances (`:domain:ui`)

- [x] 3.1 Added `inviteUrl: String? = null` and `onShareInvite: () -> Unit = {}` params to
      `StatusScreen`.
- [x] 3.2 In joined-layer states (and only there) renders `AppQrCode(inviteUrl, "Scan to join this
      event")` above the hero, and adds `ShareButton` to the bottom-end cluster before `LeaveButton`.
      Nothing invite-related renders in `Loading`/`Setup`/`PermissionBlocked`/`Joining`/`JoinFailed`.
- [x] 3.3 `:domain:ui:jvmTest`: joined-layer states render the QR (caption) and share action; the five
      non-joined states render neither; joined-without-url renders neither; activating share invokes
      `onShareInvite`.

## 4. iOS wiring (`:app:ios`)

- [x] 4.1 In `SnapSyncRoot`, bound `share = { url -> presentShareSheet(url) }` (a
      `UIActivityViewController` from the top-most view controller, fire-and-forget) and injected it
      into `StatusContainerHost`. The container's `inviteUrl` is read directly by the view.
- [x] 4.2 In `MainViewController`, collect `host.inviteUrl` and pass it plus
      `onShareInvite = host::onShareInvite` to `StatusScreen`.
- [x] 4.3 `compileIosMainKotlinMetadata` green (run in §7).

## 5. Desktop harness (`:app:desktop`)

- [x] 5.1 No `PanelController` change needed — the joined-layer presets already force `CANNED_CONFIG`
      (a fixed sample `eventId`) via `forgeSync`, so `host.inviteUrl` is non-null there and the QR
      renders. `Main.kt` collects `host.inviteUrl` and passes it to `StatusScreen`.
- [x] 5.2 Wired the harness `share` lambda to a clipboard-copy + `println` stub (test equipment; no
      native share, no control-panel control).

## 6. Docs

- [x] 6.1 `docs/design.md`: §1 scope now says the app displays the joined event's QR (no longer "does
      not display QR codes"); added an §1 invite bullet + reworded the non-goal; added a §5 "Invite
      affordance in the joined layer" entry (QR + caption + share, `inviteUrl` screen-level param,
      `share` lambda, `AppQrCode`/`ShareButton`, action cluster, qrose containment, QR-is-capability).

## 7. Verify

- [x] 7.1 `./gradlew build` → **BUILD SUCCESSFUL** (all targets compile + JVM/offscreen UI tests
      green). The 13 new invite/share cases (`:domain:ui:jvmTest` + `:domain:presentation:jvmTest`)
      execute and pass on a forced `--rerun-tasks`.
- [x] 7.2 `./gradlew compileIosMainKotlinMetadata` → **BUILD SUCCESSFUL** (iOS source sets, incl. the
      `SnapSyncRoot` share wiring + qrose, compile on Linux).
