# adapter/ios: the iOS adapters

PhotoKit, the Keychain, App Attest, the background `URLSession`s and the iOS storage adapters. Structure and
rationale: `docs/architecture.md` §11 and the module build files. The Xcode host's own rules are in
`iosApp/AGENTS.md`, the composition roots' in `app/ios/AGENTS.md`.

## Limited (partial) photo access: three measured platform facts

Limited access is a first-class grant (capability `photo-access`): the user's hand-picked selection IS the
membership's own-photo scope. Three measured platform facts shape the implementation. Violating any of them reads as
"mysteriously broken", with no error anywhere.

1. **The app never raises iOS's limited-library prompt itself, and nothing may be designed on when iOS does.** The
   prompt is iOS's automatic *"Select More Photos… / Keep Current Selection"* nudge to widen a partial selection. It
   is **not** a guard on reads (the app can only ever read the selection). The app suppresses it with
   `PHPhotoLibraryPreventAutomaticLimitedAccessAlert = true` in `iosApp/iosApp/Info.plist` **and**
   `iosApp/BackgroundUploadExtension/Info.plist`. The key is read from each process's own bundle, and the extension,
   launched by the OS under `.limited` when a full-grant registration survived, evaluates the prompt at launch
   before any of our code runs (measured 2026-09-21; one such launch showed it). The app owns the route instead: the
   status screen's "Choose more photos" picker.
   - **Settled** on the SE2 across probes: reads of an unchanged library, however many, and the app's own creations
     (imports, album creation and adds; created assets join the selection) raise no prompt.
   - **Not settled**: a photo taken *outside* the selection. The key **leaked** on iOS 26.5 and 26.5.2 (July and
     August probes: queued prompts after a camera photo, surviving SIGKILL onto the home screen) and **held** on
     26.6.2 (2026-09-21: one camera photo, 11 reads over 4 launch-and-kill cycles, zero prompts). So never assert
     "every photo costs a prompt", and never justify a read strategy as alert suppression. Record:
     `changes/archive/2026-09-21-correct-limited-access-alert-rule` (history table in its design).
   - Reads still happen ONLY on the start's baseline and the `Gallery` selection observer's `onChanged` emissions.
     The observer opens on composition, so a background start reads the selection too (measured prompt-free on the
     SE2/26.6.2, 2026-09-30: `changes/timely-background-receiving` D6). Every upload cycle's discovery is fed the
     in-memory selection (the read plan of `OwnPhotos`, `domain/data`), never a walk: under a partial grant the
     selection IS the scope, and a walk is a round-trip that buys nothing.
   - ⏰ Re-measure at the next iOS major. The evidence is one device, and one probe on 26.6.x.
2. **The ≥26.1 PhotoKit extension cannot be REGISTERED under `.limited`.** `setUploadJobExtensionEnabled` is refused
   in *both* directions with `PHPhotosErrorAccessUserDenied` 3311 (measured, SE2/26.6; the older "registration
   succeeds and lies" reading is contradicted by measurement). Under a partial grant the app's uploader does the
   uploading: it creates under any usable grant, and `extensionRegistrable` is false.
   ⚠️ A registration made under a FULL grant **survives** a downgrade (the deregistration is refused too), and **the
   OS still invokes it** under `.limited`: measured SE2/26.6, 2026-09-21, `process()` ran 4 s after a new photo
   joined the selection. The extension's own entry gate is what stops it (it withholds without `GRANTED`). Never
   rely on "the OS does not invoke it".
3. **Asset and album creation is unrestricted under `.limited`**, so downloads and the event album need no special
   handling. Album structure is unreadable, so the album denylist could not judge a selection anyway, and since
   `changes/archive/2026-10-15-limited-selection-decides` no origin rule does.

Decision record: `openspec/changes/archive/2026-07-20-accept-limited-photo-access/` (`PROBE-FINDINGS.md` + `LIMITED-ACCESS-DESIGN.md`).

## Reading the Apple SDK from Linux (`klib dump-metadata`)

**"I'd need a Mac to check that" is wrong for a whole class of questions.** The Kotlin/Native distribution ships
**prebuilt platform klibs**, and they are the compiler's own input, so what an Apple API *declares* is readable here
in under a second, with no Xcode:

```
K=~/.konan/kotlin-native-prebuilt-linux-x86_64-$(grep -oP '^kotlin = "\K[^"]+' gradle/libs.versions.toml)
$K/bin/klib dump-metadata $K/klib/platform/ios_arm64/org.jetbrains.kotlin.native.platform.Photos
```

It answers **enum case sets and their values**, **property nullability**, selector encodings and deprecations. It
settled three things in one sitting (2026-08-09) that had each been treated as device-only:
- `PHAssetResourceUploadJobState` declares exactly five cases, so a table's `else` was absorbing a *known* state,
  not just hypothetical ones.
- `PHAssetResourceUploadJob.destination` and `.resource` are declared **non-null** and are nil at runtime, so the
  two widenings in `IosPhotoKitUploadPlatform` are load-bearing, not redundant `?`.
- `PHAssetResourceUploadJob` has no `statusCode`, so its `responseHeaderFields` cannot yield one.

`:test:architecture`'s `PlatformVocabularyPinTest` pins the declared sets from this same source, so a case Apple
adds fails the Kotlin bump rather than reaching a device untaught.

⚠️ This does not contradict the law "a platform-capability claim is settled by a compile, not by a symbol table".
They answer different questions:

| question | authority |
|---|---|
| *can I **call** this?* | the symbol table over-promises (visible but `internal`, present but unlinkable): **settle it with a compile** |
| *what does this **declare**?* | the klib **is** the compile's input, so it is authoritative by construction |
| *what does the **device** do?* | neither: only a measurement |

The third row keeps this honest: the runtime may return a value no header carries, and the prebuilt klib reflects
the SDK **Kotlin/Native** was built against, not the iOS version on the phone. So the declared vocabulary tracks the
**Kotlin version** in `libs.versions.toml`, not the locally installed Xcode, which is why the pin fires on a Kotlin
bump.

## Logging from iOS

⚠️ **Never use `NSLog` for diagnostics**, in Kotlin or Swift: an interpolated format is a *dynamic format string*,
which os_log redacts wholesale (the full rule is in `iosApp/AGENTS.md`). The device-log sinks (`FileLogSink`,
`PublicNSLogSink`) and `IosThreadLabel` live in `:adapter:ios:ext-safe`.
