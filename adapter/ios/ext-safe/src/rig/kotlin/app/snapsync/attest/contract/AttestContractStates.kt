package app.snapsync.attest.contract

import app.snapsync.attest.ANSWER_BOUND
import app.snapsync.attest.AppAttestApi
import app.snapsync.attest.IosDeviceIntegrity
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.DeviceIntegrityState
import app.snapsync.contracts.Entered
import app.snapsync.contracts.proxy.recorded
import app.snapsync.ports.DeviceIntegrity
import kotlin.time.Duration

/*
 * The states the entitled app can put App Attest in, per clause. Shared by the device
 * bindings (which record) and the replay bindings (which replay), so seeding and cleanup make IDENTICAL calls
 * in both — otherwise every replay would diverge on the setup rather than on the adapter.
 */

/** Why neither the device binding nor its replay can present UNSUPPORTED. */
internal const val DEVICE_UNREACHABLE_UNAVAILABLE =
    "the app process on a device has App Attest (the kexe host covers UNAVAILABLE)"

/**
 * An [IosDeviceIntegrity] over [api], for the one state the entitled app presents, recorded over [log]. A device run
 * waits as a composition does; a replay of a call App Attest never answered passes a shorter [answerWithin].
 */
internal fun integrityInState(
    api: AppAttestApi,
    state: DeviceIntegrityState,
    log: CallLog,
    answerWithin: Duration = ANSWER_BOUND,
    afterDispose: () -> Unit = {
    },
): Entered<DeviceIntegrity> =
    if (state == DeviceIntegrityState.UNAVAILABLE) {
        Entered.Unreachable(DEVICE_UNREACHABLE_UNAVAILABLE)
    } else {
        Entered.Ready(IosDeviceIntegrity(api, answerWithin).recorded(log), afterDispose)
    }
