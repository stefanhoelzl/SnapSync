# Tasks

## 1. Deployment value (D1)

- [x] 1.1 Add the `playStoreUrl` inventory key (renderings `SITE`, `PROPS`) to `scripts/resolve-deployment.py` with its doc. Add `"playStoreUrl": ""` to `deployments/components/android.json`, and correct `appStoreUrl`'s doc (no `/join` redirect; two readers). Verify with `python3 scripts/resolve-deployment.py` for `prod` and `local`: the site's `deployment.json` and `build/deployment.properties` both carry the key, and the api bundle does not.
- [x] 1.2 Add the validation: a non-empty value must equal `https://play.google.com/store/apps/details?id=<androidPackageName>`. Extend `scripts/resolve_deployment_test.py` to cover the rendering set, the empty value, a foreign URL and an extra query, and verify the tests pass.
- [x] 1.3 Pass `playStoreUrl` into `BuildConfig.PLAY_STORE_URL` in `app/android/build.gradle.kts`. Verify with `./gradlew :app:android:compileDebugKotlin` and the generated `BuildConfig`.

## 2. Store link on the update notice (D6)

- [x] 2.1 Add `StoreLink` and `StoreKind` to `:domain:model`. Replace `BuildInfo.appStoreUrl` with `store: StoreLink?` and follow it through `IosBuildInfo`, `AndroidBuildInfo` (`GOOGLE_PLAY` over the new `playStoreUrl` constructor parameter, `null` when empty), `BuildInfoMock`, `ComposedApp`, `StatusSources`, `StatusContainerHost` and `Layer.UpdateRequired`, the rig's `JvmRigHost` and the desktop `WorldInspectorController`. Verify with `./gradlew compileIosMainKotlinMetadata` and a JVM compile.
- [x] 2.2 Label the notice's button from the kind in `CreateEventScreen` ("Open the App Store" / "Open Google Play"). Update `VersionGateHostTest` and `HostStatusActionsTest`, and add a `:ui:screens` test asserting each label and no button without a link. Verify with `./gradlew :domain:presentation:jvmTest :ui:screens:jvmTest`.
- [x] 2.3 Pass `BuildConfig.PLAY_STORE_URL` to `AndroidBuildInfo` in `SnapSyncRoot`, and update its KDoc ("no App Store page" becomes the Play page once published). Verify with `./gradlew :app:android:assembleDebug`.

## 3. Referrer translation (D4)

- [x] 3.1 Add `inviteLinkFromInstallReferrer` beside the codec in `EventLink.kt`, with KDoc naming the organic case. Add commonTest cases: a valid referrer gives the canonical link; organic, garbage, wrong version and empty give `null`; extra params are dropped. Verify with `./gradlew :domain:model:jvmTest`.

## 4. Android install-referrer reader (D5)

- [x] 4.1 Add the Play Install Referrer library to `gradle/libs.versions.toml` and to `:adapter:android`. Verify with `./gradlew :adapter:android:dependencies` and a compile.
- [x] 4.2 Implement `AndroidInstallReferrer` in `link/` over the internal `ReferrerSource` seam (the real one over `InstallReferrerClient`) with its `SharedPreferences` record, delivering through a new `AndroidLinks` entry on hook `installReferrer`. KDoc must cover once-per-install, record-after-delivery, `TryLater` versus `NeverAvailable`, and a data clear re-offering. Verify with a compile.
- [x] 4.3 Add the `installReferrer` hook to `PlatformAdapters`: the production set supplies the reader, the rig set a no-op. Have `MainActivity.onCreate` (not restored) call the root, which runs the hook once per process. Verify that `KotlinShellGuardTest` and `detektAppShell` stay green under `./gradlew build`.
- [x] 4.4 Add an `androidDeviceTest` for the reader over a fake `ReferrerSource` and real `SharedPreferences`: delivered once, silent after handled, organic recorded but not delivered, `NeverAvailable` recorded, `TryLater` retried. Verify with `./gradlew androidPlatformTest` (or `connectedAndroidDeviceTest` on the `android-emulator` skill's emulator).

## 5. Site (D2, D3)

- [x] 5.1 Commit Google's official English "Get it on Google Play" badge under `site/src/assets/`. Add `PlayStoreButton.astro`, which renders nothing when `playStoreUrl` is empty. Put it beside `AppStoreButton` in a wrapping row, height-matched, on `index.astro` and in both views on `join.astro`. Verify that `npm run check` passes (types, formatting, self-containment), and look at a build with a set URL through `npm run dev` or `astro preview` in light and dark mode, as `ch ws browser` shows it.
- [x] 5.2 Add `site/src/lib/` with the pure href builder. Change `decodeFragment` to return `{ eventId, d }`, and make the island set the `#app` badge's href only on a valid decode. Add a `deno test` for the builder and wire it into `npm run check`. Verify that the test passes, that the built `join/index.html` has no referrer in it, and in a browser that a valid fragment rewrites only the `#app` badge.
- [x] 5.3 Add the referrer disclosure to the Privacy Policy in `index.astro`: following the event page's Google Play button hands the invite to Google, and nothing else does. Verify that `npm run check` passes and the text renders.

## 6. Specs, docs and integration

- [x] 6.1 Edit the main specs' Purpose paragraphs directly (deltas cannot carry them): `event-site` drops "SnapSync is iPhone-only", and `web-site` mentions Google Play beside the App Store. Verify with `npx --yes @fission-ai/openspec@1.13.2 validate --specs --strict` and the identifier grep from `openspec/config.yaml`.
- [x] 6.2 Record the new key and the dormant-until-phase-6 switch in `docs/deployment.md`, and the reader's device test in `docs/testing.md` where the Android device tests are listed. Verify by reading the rendered sections.
- [x] 6.3 Regenerate `architecture/` (`./gradlew architectureDiagrams`) and run `./gradlew build`. Verify both are green with the diagrams committed.
- [x] 6.4 On the `android-emulator` skill's emulator, install the rig build and open the update notice with no Play URL. Verify that it shows no button, and that nothing about links changed: an invite tapped on the emulator still opens the join screen.
