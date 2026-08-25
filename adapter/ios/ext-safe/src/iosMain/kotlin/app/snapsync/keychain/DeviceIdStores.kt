package app.snapsync.keychain

import app.snapsync.ports.SecureStore

/**
 * **Where the device id is kept, chosen by COMPILATION TARGET rather than at runtime**
 * (capability `device-identity`).
 *
 * `iosArm64` — every shipped binary — keeps the addressed shared-Keychain item, unchanged and
 * unchangeable from here. `iosSimulatorArm64` keeps a file in the App-Group container instead,
 * because on a simulator the Keychain group **cannot exist**: carrying `keychain-access-groups`
 * makes the app un-launchable (measured 2026-08-09 in the unprefixed form, in the correctly
 * prefixed form, on an ad-hoc signature, and signed with the real "Apple Development" identity),
 * and omitting it makes every read of the addressed item fail with `errSecMissingEntitlement`
 * (-34018). That is an `Unavailable`, not an `Absent` — and `resolveOrMint` refuses to mint on
 * unavailability, deliberately, because on a **device** that state means *locked*. So a simulator
 * would resolve no id at all, enroll nowhere, and join nothing.
 *
 * ## Why a target and not a runtime check
 *
 * `iosSimulatorArm64` is not a guess about the host: it is a compilation target whose output only
 * ever runs on a simulator. A device binary therefore contains **no route** to the store below —
 * *"contained by compilation, not by a runtime check"* (spec `module-architecture`). The
 * alternatives all require production to decide, at runtime, that it is on a host where the group
 * is unreachable: asking the host is the `OsFacts` pattern deleted one change earlier, and
 * classifying the `OSStatus` reopens what `reshape-keychain-port` D3 closed on purpose. Both put a
 * branch on a device that can be taken wrongly while locked, which is how a device acquires a
 * second identity and orphans its byte partition and its ledger.
 *
 * ## What does NOT change
 *
 * The contract does. `resolveOrMint`'s normative order, the refusal to mint on a read error, the
 * one-value-per-process cache and the resolution log are all target-independent: only the
 * `SecureStore` behind them moves. Both compositions ([app.snapsync.keychain.KeychainDeviceIdentity]
 * from `SnapSyncRoot` and from `UploadExtensionRoot`) pick their target's stores up through these
 * defaults without naming a store, so the app and the extension agree on any target — on a
 * simulator because the App-Group container is shared between them by construction.
 *
 * **The simulator binding is a precondition, not coverage.** A regression in the Keychain binding
 * cannot surface on a simulator, because that binary does not contain it. Decision record:
 * `changes/add-simulator-rig-host` (D6).
 */
internal expect fun deviceIdPrimaryStore(): SecureStore

/**
 * The **adoption** source consulted only when [deviceIdPrimaryStore] reports a genuine absence — on
 * a device, the unscoped view that can still see an id an older build placed in this process's own
 * `application-identifier` group.
 *
 * A target with no such history answers `Absent`, which makes the adoption branch a no-op and leaves
 * minting to the branch below it. It is a separate function rather than a nullable because
 * `resolveOrMint`'s ordering treats "could not look" on the legacy read as disqualifying too, and a
 * store that says `Absent` states that honestly where a `null` would have to be interpreted.
 */
internal expect fun deviceIdLegacyStore(): SecureStore
