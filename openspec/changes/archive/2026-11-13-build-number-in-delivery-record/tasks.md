# Tasks

## 1. The api records `build`

- [x] 1.1 Add `build: /^[0-9]{1,10}$/` to `SHAPES` in `api/src/delivery-context.ts` (beside `flavour`) and name the
  key in its header comment's example; verify `api/test/request-log.test.ts`: the "every readable key once" test
  sends `build=2140` and its line shows `build=2140`, and the "unknown or unreadable" test sends `build=?` and
  `build=12a` and records neither
- [x] 1.2 Add `build` (digits: the build number — `run_number + 2000` on CI, `1` for a local build) to the "every
  app request" row of the delivery-record table in `docs/architecture.md`; verify the row lists every key of
  `SHAPES`, in its order

## 2. The app sends `build`

- [x] 2.1 Add `DeliveryFacts.build(buildNumber)` in `core/domain/.../delivery/DeliveryFacts.kt` — `build` to the number
  when it is all digits, nothing otherwise (as `os`) — and have `BuildContext` in
  `core/data/.../backend/DeliveryContext.kt` add it after `flavour`, updating its KDoc's key list; verify
  `DeliveryFactsTest` (`2140` kept; `?` and blank left out) and `DeliveryContextTest` (the iOS header gains
  `build=2140` after `flavour=release`, the Android one `build=1` after `flavour=dev`; an unknown `buildNumber`
  sends none)
- [x] 2.2 Verify `./gradlew compileIosMainKotlinMetadata` and that both `BackendGraph` and `ExtensionGraph` still
  compose `BuildContext` (no graph change needed)

## 2b. `os` splits into `os` and `os_version` (design D4)

- [x] 2b.1 App: `DeliveryFacts.os` writes `os` (the platform, always) and `os_version` (when the OS states one);
  verify `DeliveryFactsTest` and `DeliveryContextTest` headers read `os=ios;os_version=18.6;…`, and an unreadable
  version still sends `os`
- [x] 2b.2 api: `SHAPES` takes `os: ["ios", "android"]` and `os_version`; the header comment states why a value is
  shape-checked; verify `api/test/request-log.test.ts` records both, and drops the combined `os=ios/18.6`
- [x] 2b.3 `docs/architecture.md`: the delivery-record row lists `os` and `os_version`, and "Never refused, never
  trusted" says why values are shape-checked; verify against `SHAPES`

## 3. What the user is told

- [x] 3.1 Privacy Policy (`site/src/pages/index.astro`, "A record of how photos are delivered"): "the app's version"
  becomes "the app's version and build number"; verify the site builds and every scenario of "The Privacy Policy
  states what leaves the device" still holds on the rendered page
- [x] 3.2 `metadata/play/declarations.md`, the delivery record entry: name the build number beside the app version;
  verify no store form changes (still diagnostics, already declared)

## 4. Integration

- [x] 4.1 With the sync/archive (not before — `VerifiesGateTest` reads the main specs): claim the new scenario
  "Two builds of one version" with `@Verifies` on an api test (`request-log.test.ts`, "two builds of one version are told apart by their build"); verify `./gradlew build`
- [x] 4.2 Against a local backend (`local-backend`) on a simulator or the emulator: a request's record in
  `deno task logs --field build=<n>` shows the build's number, from both the app and (iOS) a background transfer
  - Measured 2026-11-13, Android emulator (API 36), `journeys run --platform android` over the local api: all 45 app
    requests — attest, create, join, manifest, union reads, byte uploads (`PUT …/files/…`), downloads — recorded
    `os=android os_version=16 flavour=dev build=1`. The run itself failed 2 of 3 journeys for reasons outside this
    change: the emulator's `DCIM/Camera` held 60 photos left by earlier runs (the union check saw 4, expected 2), and
    the swipe-away journey's process did not end within 10 s.
  - Measured 2026-11-13, iOS 26.5 simulator on the macOS runner, `journeys run --platform ios` (every contract and
    journey passed): all 41 app requests — the same routes, byte uploads included — recorded
    `os=ios os_version=26.5 flavour=dev build=1`; none the combined form. NOT measurable there: a background
    `URLSession` (the simulator binds a default session) and the upload extension's own bundle read (its root runs
    in the app's process) — both device-only (`snapsync-device`, the SE2 under its lock).
