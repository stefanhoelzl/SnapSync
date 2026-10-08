package app.snapsync.contract

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CONTRACT_REFUSED
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.ProcessInfoContract
import app.snapsync.contracts.ProcessInfoState
import app.snapsync.contracts.Recorder
import app.snapsync.contracts.Replayer
import app.snapsync.contracts.proxy.recorded
import app.snapsync.ports.ProcessInfo
import app.snapsync.protection.IosProcessInfo
import app.snapsync.protection.ProtectedDataApi
import app.snapsync.protection.SystemProtectedDataApi
import platform.posix.usleep
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/*
 * `UIApplication.isProtectedDataAvailable` as TEXT, and the entitled device's binding of `ProcessInfoContract` with its
 * screen locked (`docs/architecture.md`, "Hosts CI cannot reach are recorded at the operating-system boundary and
 * replayed on every build").
 *
 * Compiled into this module's `iosMain` only under `-Psnapsync.rig=true` (where the device records), and into `iosTest`
 * otherwise (where CI replays) — one file, so the recorder and the replayer cannot spell a call differently.
 */

private const val READ = "isProtectedDataAvailable()"

/** Passes every read to [real] and records it, with iOS's answer, in the clause block [recorder] has open. */
internal class RecordingProtectedDataApi(
    private val real: ProtectedDataApi,
    private val recorder: Recorder,
) : ProtectedDataApi {
    override suspend fun available(): Boolean = real.available().also { recorder.record(READ, "$it") }
}

/** Answers every read from one clause's recorded block, exactly and in order. */
internal class ReplayingProtectedDataApi(private val replayer: Replayer) : ProtectedDataApi {
    override suspend fun available(): Boolean = replayer.answer(READ).toBooleanStrict()
}

/** The precondition a locked run names: `ProcessInfo@IOS_DEVICE_APP.LOCKED.rec`. */
internal const val LOCKED = "LOCKED"

/** The real [IosProcessInfo] in the app on a device whose screen a person locked, recording each read. */
internal class DeviceLockedProcessInfoBinding(private val recorder: Recorder) : Binding<ProcessInfoState, ProcessInfo> {
    override val host = Host.IOS_DEVICE_APP
    override val kind = BindingKind.Live
    override val reaches = setOf(ProcessInfoState.LOCKED)
    override val precondition = LOCKED

    override fun create(state: ProcessInfoState, clauseId: String, log: CallLog): Entered<ProcessInfo> {
        // A state this host does not present in this run: the unlocked ones run live on the simulator app.
        if (state !in reaches) {
            return Entered.Unreachable(
                "this run records the phone locked; $state runs live elsewhere",
            )
        }
        recorder.open(clauseId)
        return Entered.Ready(IosProcessInfo(RecordingProtectedDataApi(SystemProtectedDataApi, recorder)).recorded(log))
    }
}

/** How long a person has to lock the phone once the run is asked for. */
private val LOCK_WINDOW = 2.minutes

/**
 * Runs the process-read contract on a phone whose screen is locked. The run is asked for with the phone unlocked; it
 * then holds background time — locking sends the app to the background — and waits, reading through the real seam
 * without recording, for the protected data to seal (iOS seals it about ten seconds after the lock, with a passcode set).
 * Only then does the clause run. A phone not locked within [LOCK_WINDOW] refuses the run.
 */
internal fun recordProcessInfoLocked(): String = whileHoldingBackgroundTime("contract.ProcessInfo.locked") {
    val started = TimeSource.Monotonic.markNow()
    while (protectedDataReadable() && started.elapsedNow() < LOCK_WINDOW) usleep(POLL_MICROS)
    val sealed = !protectedDataReadable()
    if (!sealed) {
        CONTRACT_REFUSED +
            "the protected data stayed available for $LOCK_WINDOW: lock the phone (a passcode must be set) right after " +
            "asking for the run.\n"
    } else {
        recordAppOnDevice(ProcessInfoContract, null, LOCKED) { DeviceLockedProcessInfoBinding(it) }
    }
}

private const val POLL_MICROS = 500_000u
