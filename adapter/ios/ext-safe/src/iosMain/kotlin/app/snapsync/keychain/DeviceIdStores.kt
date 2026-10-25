package app.snapsync.keychain

import app.snapsync.ports.SecureStore

/**
 * **This compilation target's `SecureStore`**, chosen by COMPILATION TARGET rather than at runtime.
 *
 * Every shipped binary is `iosArm64` and gets the Keychain ([IosSecureStore]) for every slot. An
 * `iosSimulatorArm64` build cannot have the shared Keychain access group at all — an ad-hoc signed simulator app
 * carrying `keychain-access-groups` is unlaunchable, so the simulator entitlements declare none, and every shared
 * slot read then fails `errSecMissingEntitlement` (-34018): an `Unavailable`, which the identity service will
 * never mint over — so the simulator would have no device id, ever. It therefore keeps every SHARED slot (the
 * device id's, an event key's) in an App-Group file instead, and sends every other slot to the Keychain. The
 * identity's resolution order runs unchanged either way; only where a slot lives moves.
 */
expect fun platformSecureStore(): SecureStore
