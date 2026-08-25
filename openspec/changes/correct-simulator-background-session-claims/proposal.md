## Why

The contract of record asserts, in three places, that a background `URLSession` runs on the iOS
simulator. It does not. The 2026-08-09 measurement those claims rest on aimed an upload task at a
**closed** port and read the delegate's `(unknown error)` as proof the task had executed — on the
stated reasoning that "a refusal still proves the session executed the task". A refusal is
`NSURLErrorCannotConnectToHost` (-1004); "unknown error" is `NSURLErrorUnknown` (**-1**), and -1 is
what a simulator returns when no background session was ever created. The probe never obtained the
positive its own criterion required.

Re-measured 2026-08-25 on the same host versions (macOS 26.5.2 / Xcode 26.6, iOS 26.5 and 26.2), with a
foreground control succeeding against the same URL in the same process: every background variant fails
with -1, and `nsurlsessiond`'s own log gives the cause. This is the second wrong claim in this spot, in
the opposite direction from the first, and both survived because nothing re-measured them.

## What Changes

- Correct the three false claims in `ios-url-session-upload`: the Purpose's
  "**simulator-testable end-to-end** (a background `URLSession` runs in the simulator)", the module
  requirement's "Because a background `URLSession` runs in the iOS simulator, the transport MAY be
  exercised end-to-end in the simulator", and the forcing proof attached to
  "The app-driven tier uses one transport on every host".
- **KEEP** that requirement's normative content unchanged — one transport, no host determination, no
  simulator-specific session configuration — and replace its **ground**. It currently reads "the
  downgrade this requirement's predecessor provided for defended nothing", which was true only while the
  hosts were believed identical. They are not, so the requirement now stands on a different and stronger
  footing: a host-specific downgrade would make the simulator *appear* to work while removing the only
  host that exercises `__NSURLBackgroundSession` — the class `fix-download-session-lifecycle` D5's defect
  lives in.
- Record the measured mechanism as the new forcing proof, with its expiry trigger.
- Correct `IosUrlSessionUploadPlatform`'s comment, which repeats the same false claim in shipped code.
- Supersede `2026-08-09-delete-simulator-session-downgrade` **D1** without editing that archive.

**No behaviour changes.** No transport, composition, adapter or test behaviour is altered. Anything that
compiles and passes today does so after this change.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `ios-url-session-upload`: the Purpose's simulator-testability claim, the MAY clause permitting
  end-to-end transport exercise on a simulator, and the forcing proof under "The app-driven tier uses one
  transport on every host". The requirement's SHALLs are unchanged; its justification and one scenario's
  consequence are.

`photo-download` was checked and needs no delta: its only simulator references are about **unit tests**
running on `iosSimulatorArm64`, which remains true.

## Impact

- `openspec/specs/ios-url-session-upload/spec.md` — three passages.
- `adapter/ios/app-only/src/iosMain/kotlin/app/snapsync/ios/urlsession/IosUrlSessionUploadPlatform.kt` —
  the session comment. Comment only; the `by lazy` session it documents is untouched.
- No code, test, or generated-diagram change. `./gradlew build` and `architectureDiagrams` outcomes are
  unchanged by construction.
- Ships on the current branch, so it lands in PR #207 alongside the measurement that produced it.
