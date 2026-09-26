package app.snapsync.attest.contract

import app.snapsync.attest.AppAttestApi
import app.snapsync.attest.IosDeviceIntegrity
import app.snapsync.contracts.AttestStoreContract
import app.snapsync.contracts.AttestStoreState
import app.snapsync.contracts.DeviceIntegrityState
import app.snapsync.contracts.Entered
import app.snapsync.keychain.IosSecureStore
import app.snapsync.model.SecureSlot
import app.snapsync.services.identity.AttestState
import app.snapsync.keychain.KeychainApi
import app.snapsync.ports.DeviceIntegrity
import app.snapsync.ports.AttestStore

/*
 * The states the entitled app can put App Attest and the attestation store in, per clause. Shared by the device
 * bindings (which record) and the replay bindings (which replay), so seeding and cleanup make IDENTICAL calls
 * in both — otherwise every replay would diverge on the setup rather than on the adapter.
 */

/** Why neither the device binding nor its replay can present UNSUPPORTED. */
internal const val DEVICE_UNREACHABLE_UNAVAILABLE =
    "the app process on a device has App Attest (the kexe host covers UNAVAILABLE)"

/** Why neither the device binding nor its replay can present an unreadable store. */
internal const val DEVICE_UNREACHABLE_INACCESSIBLE_STORE =
    "the entitled app runs unlocked: its Keychain is accessible (the kexe host covers INACCESSIBLE)"

/** An [IosDeviceIntegrity] over [api], for the one state the entitled app presents. */
internal fun integrityInState(api: AppAttestApi, state: DeviceIntegrityState, afterDispose: () -> Unit = {}): Entered<DeviceIntegrity> =
    if (state == DeviceIntegrityState.UNAVAILABLE) {
        Entered.Unreachable(DEVICE_UNREACHABLE_UNAVAILABLE)
    } else {
        Entered.Ready(IosDeviceIntegrity(api), afterDispose)
    }

private const val SERVICE = "app.snapsync.contract.attest"

/**
 * A fresh [AttestState] over [keychain] in [state], its two slots addressed from the clause id in the
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
    val secure = IosSecureStore(keychain)
    val token = SecureSlot(service = SERVICE, account = "$clauseId.token", shared = true)
    val keyId = SecureSlot(service = SERVICE, account = "$clauseId.keyid", shared = true)
    secure.delete(token)
    secure.delete(keyId)
    val store = AttestState(secure, tokenSlot = token, keyIdSlot = keyId)
    if (state == AttestStoreState.HOLDING) {
        store.setKeyId(AttestStoreContract.seedKeyId(clauseId))
        store.setToken(AttestStoreContract.seedToken(clauseId))
    }
    return Entered.Ready(store) {
        secure.delete(token)
        secure.delete(keyId)
        afterDispose()
    }
}
