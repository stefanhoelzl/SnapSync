package app.snapsync.attest.contract

import app.snapsync.attest.AppAttestApi
import app.snapsync.attest.IosAttestKey
import app.snapsync.attest.KeychainAttestStore
import app.snapsync.contracts.AttestStoreContract
import app.snapsync.contracts.AttestStoreState
import app.snapsync.contracts.AttestKeyState
import app.snapsync.contracts.Entered
import app.snapsync.keychain.IosKeychain
import app.snapsync.keychain.KeychainApi
import app.snapsync.keychain.SHARED_KEYCHAIN_ACCESS_GROUP
import app.snapsync.ports.AttestKey
import app.snapsync.ports.AttestStore

/*
 * The states the entitled app can put App Attest and the attestation store in, per clause. Shared by the device
 * bindings (which record) and the replay bindings (which replay), so seeding and cleanup make IDENTICAL calls
 * in both — otherwise every replay would diverge on the setup rather than on the adapter.
 */

/** Why neither the device binding nor its replay can present UNSUPPORTED. */
internal const val DEVICE_UNREACHABLE_UNSUPPORTED =
    "the app process on a device has App Attest (the kexe host covers UNSUPPORTED)"

/** Why neither the device binding nor its replay can present an unreadable store. */
internal const val DEVICE_UNREACHABLE_INACCESSIBLE_STORE =
    "the entitled app runs unlocked: its Keychain is accessible (the kexe host covers INACCESSIBLE)"

/** An [IosAttestKey] over [api], for the one state the entitled app presents. */
internal fun attestKeyInState(api: AppAttestApi, state: AttestKeyState, afterDispose: () -> Unit = {}): Entered<AttestKey> =
    if (state == AttestKeyState.UNSUPPORTED) {
        Entered.Unreachable(DEVICE_UNREACHABLE_UNSUPPORTED)
    } else {
        Entered.Ready(IosAttestKey(api), afterDispose)
    }

private const val SERVICE = "app.snapsync.contract.attest"

/**
 * A fresh [KeychainAttestStore] over [keychain] in [state], its two items addressed from the clause id in the
 * shared group the production items use. Starts by deleting both — a clause never sees what an interrupted run
 * left behind — and deletes both again on dispose. [afterDispose] runs last.
 */
internal fun attestStoreInState(
    keychain: KeychainApi,
    state: AttestStoreState,
    clauseId: String,
    afterDispose: () -> Unit = {},
): Entered<AttestStore> {
    if (state == AttestStoreState.INACCESSIBLE) return Entered.Unreachable(DEVICE_UNREACHABLE_INACCESSIBLE_STORE)
    val token = IosKeychain(SERVICE, "$clauseId.token", SHARED_KEYCHAIN_ACCESS_GROUP, keychain)
    val keyId = IosKeychain(SERVICE, "$clauseId.keyid", SHARED_KEYCHAIN_ACCESS_GROUP, keychain)
    token.delete()
    keyId.delete()
    val store = KeychainAttestStore(tokenItem = token, keyIdItem = keyId)
    if (state == AttestStoreState.HOLDING) {
        store.setKeyId(AttestStoreContract.seedKeyId(clauseId))
        store.setToken(AttestStoreContract.seedToken(clauseId))
    }
    return Entered.Ready(store) {
        token.delete()
        keyId.delete()
        afterDispose()
    }
}
