package app.snapsync.attest.contract

import app.snapsync.attest.AppAttestApi
import app.snapsync.attest.IosDeviceIntegrity
import app.snapsync.contracts.AttestStoreContract
import app.snapsync.contracts.AttestStoreState
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.DeviceIntegrityState
import app.snapsync.contracts.Entered
import app.snapsync.contracts.proxy.recorded
import app.snapsync.keychain.IosSecureStore
import app.snapsync.keychain.KeychainApi
import app.snapsync.model.SecureSlot
import app.snapsync.ports.AttestStore
import app.snapsync.ports.DeviceIntegrity
import app.snapsync.services.identity.AttestState

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

/** An [IosDeviceIntegrity] over [api], for the one state the entitled app presents, recorded over [log]. */
internal fun integrityInState(
    api: AppAttestApi,
    state: DeviceIntegrityState,
    log: CallLog,
    afterDispose: () -> Unit = {
    },
): Entered<DeviceIntegrity> =
    if (state == DeviceIntegrityState.UNAVAILABLE) {
        Entered.Unreachable(DEVICE_UNREACHABLE_UNAVAILABLE)
    } else {
        Entered.Ready(IosDeviceIntegrity(api).recorded(log), afterDispose)
    }

private const val SERVICE = "app.snapsync.contract.attest"

/**
 * A fresh [AttestState] over [keychain] in [state], its two slots addressed from the clause id in the
 * shared group the production items use, handed to the clause through its recording proxy over [log]. Starts by
 * deleting both — a clause never sees what an interrupted run left behind — and deletes both again on dispose.
 * [afterDispose] runs last.
 */
internal fun attestStoreInState(
    keychain: KeychainApi,
    state: AttestStoreState,
    clauseId: String,
    log: CallLog,
    afterDispose: () -> Unit = {},
): Entered<AttestStore> {
    if (state == AttestStoreState.INACCESSIBLE) return Entered.Unreachable(DEVICE_UNREACHABLE_INACCESSIBLE_STORE)
    val secure = IosSecureStore(keychain)
    val token = SecureSlot(service = SERVICE, account = "$clauseId.token", shared = true)
    val keyId = SecureSlot(service = SERVICE, account = "$clauseId.keyid", shared = true)
    secure.delete(token)
    secure.delete(keyId)
    val bare = AttestState(secure, tokenSlot = token, keyIdSlot = keyId)
    // Entering the state is the binding's, not the clause's: through the bare store, so it satisfies no claim.
    if (state == AttestStoreState.HOLDING) {
        bare.setKeyId(AttestStoreContract.seedKeyId(clauseId))
        bare.setToken(AttestStoreContract.seedToken(clauseId))
    }
    return Entered.Ready(bare.recorded(log)) {
        secure.delete(token)
        secure.delete(keyId)
        afterDispose()
    }
}
