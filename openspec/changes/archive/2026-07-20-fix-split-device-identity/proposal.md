# Fix the split device identity

## Why

The app process and the upload extension are running on **two different device ids**. On the SE2 on
2026-07-20 the app held `FD82A0DB…` while the extension held `DD92FAC9…`, consistently, across four
events. Both processes read successfully; neither minted. That is only possible if **two Keychain
items exist in two different access groups** and each process finds its own.

The user-visible consequence is photo duplication. `DownloadController` treats an asset as another
member's unless `asset.deviceId == myDeviceId`, so every photo this device uploads (stamped with the
*extension's* id) is re-downloaded and re-imported into the user's own library under the *app's* id.
Measured, end to end, in 21 seconds:

```
07:59:33  ext: completed  F3EFC5F5…-primary.heic          ← device uploads its own photo
07:59:46  app: reconcile: 1 union asset(s), 1 foreign planned
07:59:54  app: imported foreign asset F3EFC5F5… as 80057DA4…   ← duplicate created
08:00:22  gallery: enumerated 4 resource(s)                 ← was 2
```

The cause is that `IosKeychain` never sets `kSecAttrAccessGroup`. Placement is therefore *implicit* —
iOS picks the first entitled group **at write time** — and that has drifted across build eras. A dev
build signed through the ssh-mac re-sign inherits the provisioning profile's wildcard grant
(`E9Z8BADH58.*`), which is not a writable group name, so each process falls back to its own
`application-identifier` group. `capability device-identity` already *requires* one shared id; the
implementation never addressed the group that requirement names.

This is invisible without instrumentation: nothing logged either id, so the symptom surfaced only as
an indefinite "pending" screen and duplicated photos, nine hours after enrollment.

## What Changes

- `IosKeychain` gains an **explicit access-group** parameter. The device id addresses the shared group
  by name on every operation — read, write, delete, accessibility migration — so placement is
  deterministic instead of an emergent property of entitlement ordering.
- The device-id resolve gains a **legacy-adoption** step. An id found outside the shared group is
  adopted into it and returned verbatim; it is **never** re-minted. Ordering is strict:
  `Unavailable` → error · shared-group `Found` → use · `Absent` → unscoped read → adopt · only then
  mint.
- **The upload extension may never mint an identity.** On absence it skips its cycle and logs, rather
  than generating a second id. Minting stays the app's exclusive right.
- The `deviceIdentity` diagnostic line — resolved id plus whether it was read, adopted, or minted —
  is logged once per process by **both** binaries. Its absence is why this went unseen.
- The ssh-mac dev re-sign builds entitlements from the repo's own `.entitlements` files instead of
  resolving them out of the provisioning profile, and asserts **no wildcard** survives into a signed
  binary. This is the same defect class as the documented `associated-domains` hazard; the assertion
  catches both, and whatever wildcard key appears next.
- Prose in `KeychainDeviceIdentity` and `IosKeychain` asserting the id is shared across processes and
  survives reinstall is corrected — the device falsified both.
- **BREAKING (state, not API):** on an already-split install the extension adopts the app's id, so
  bytes previously uploaded under the extension's id are no longer recognised as this device's own and
  are re-imported once. Accepted deliberately: the installed base is internal TestFlight only, and the
  app's id is what backend membership and push registration are registered under. Photos already
  duplicated are left alone — `deleteAssets` always raises a system confirmation, so no silent cleanup
  is possible.

Out of scope, deferred to its own change: the extension's 390-invocation fail-loop and its
`cycle finished — COMPLETED` on cycles that created no jobs.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `device-identity`: the shared access group SHALL be addressed **explicitly** rather than relied upon
  as a default; an id found outside it SHALL be adopted rather than re-minted; and minting SHALL be
  confined to the app process — the extension SHALL defer instead.
- `architecture-guards`: the shared Keychain access group joins the pinned runtime-identity literals,
  and the deliberate unscoped exception (the legacy config reader) is pinned as an exception.

## Impact

- **Code:** `adapter/ios/ext-safe/.../keychain/IosKeychain.kt`,
  `.../keychain/KeychainDeviceIdentity.kt`, `domain/.../ports/Keychain.kt` (the `resolveOrMint`
  outcome seam, already added for the diagnostic), `app/ios/extension/.../UploadExtensionRoot.kt`.
- **Untouched by design:** the attest pair (demonstrably working cross-process — the extension cannot
  self-attest, so it is already reading the app's token), the album map (a self-healing cache; only
  the app creates albums), and `KeychainConfigReader`, whose whole purpose is to find an item an older
  build left *anywhere* and which therefore must stay unscoped.
- **Dev infrastructure:** the ssh-mac re-sign block in `CLAUDE.md`; optionally the same wildcard
  assertion in `ios.yml`.
- **Tests:** `:domain` `commonTest` for the resolve ordering; a `RuntimeIdentityTest` pin for the group
  literal. Keychain itself is untestable in a Kotlin/Native test binary (`securityd` refuses a
  non-bundle caller, `errSecNotAvailable`), so on-device verification is the two `deviceIdentity` log
  lines agreeing.
- **Risk:** none of the design rests on *which* group currently holds which id. The strict ordering is
  correct under every placement, and cannot mint a third identity or destroy an existing one.
