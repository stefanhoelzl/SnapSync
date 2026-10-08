package app.snapsync.contract

import app.snapsync.background.BackgroundTimeApi
import app.snapsync.background.IosBackgroundTime
import app.snapsync.background.SystemBackgroundTimeApi
import app.snapsync.contracts.BackgroundTimeContract
import app.snapsync.contracts.BackgroundTimeState
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CONTRACT_REFUSED
import app.snapsync.contracts.Divergence
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.Recorder
import app.snapsync.contracts.Replayer
import app.snapsync.ports.BackgroundTime
import co.touchlab.kermit.Logger
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.posix.usleep
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/*
 * `UIApplication`'s background-task pair as TEXT — the two calls and the expiry the system delivers — and the entitled
 * device's binding of `BackgroundTimeContract` with the app on the home screen (`docs/architecture.md`, "Hosts CI cannot
 * reach are recorded at the operating-system boundary and replayed on every build").
 *
 * Compiled into this module's `iosMain` only under `-Psnapsync.rig=true` (where the device records), and into `iosTest`
 * otherwise (where CI replays) — one file, so the recorder and the replayer cannot spell a call differently.
 */

private fun beginCall(name: String) = "beginBackgroundTask(name=$name)"

// The identifier is the system's own and moves with every run; the adapter only hands it back, so it is masked.
private const val END_CALL = "endBackgroundTask(id=<masked>)"
private const val GRANTED = "granted id=<masked>"
private const val REFUSED = "invalid"

private fun expired(name: String) = "expired(name=$name)"

/** Passes every call to [real] and records it, with iOS's answer, and every expiry iOS delivers, as an event. */
internal class RecordingBackgroundTimeApi(
    private val real: BackgroundTimeApi,
    private val recorder: Recorder,
) : BackgroundTimeApi {
    override fun begin(name: String, expirationHandler: () -> Unit): ULong? =
        real.begin(name) {
            recorder.event(expired(name))
            expirationHandler()
        }.also { recorder.record(beginCall(name), if (it == null) REFUSED else GRANTED) }

    override fun end(identifier: ULong) {
        real.end(identifier)
        recorder.record(END_CALL, "done")
    }
}

/**
 * Answers every call from one clause's recorded block, exactly and in order, and delivers each recorded expiry to the
 * handler of the task it names — after the call it followed, on another thread, as the system does.
 */
internal class ReplayingBackgroundTimeApi(private val replayer: Replayer) : BackgroundTimeApi {
    private val handlers = mutableMapOf<String, () -> Unit>()
    private var next = 1uL

    override fun begin(name: String, expirationHandler: () -> Unit): ULong? {
        val answer = replayer.answer(beginCall(name))
        handlers[name] = expirationHandler
        deliverDue()
        return if (answer == REFUSED) null else next++
    }

    override fun end(identifier: ULong) {
        replayer.answer(END_CALL)
        deliverDue()
    }

    private fun deliverDue() = replayer.takeEvents().forEach { event ->
        val handler = handlers.entries.firstOrNull { expired(it.key) == event }?.value
            ?: throw Divergence("a recorded expiry names no task begun under that name: $event")
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) { handler() }
    }
}

/** The real [IosBackgroundTime] in the app on a device a person sent to the home screen, recording every call. */
internal class DeviceBackgroundTimeBinding(
    private val recorder: Recorder,
) : Binding<BackgroundTimeState, BackgroundTime> {
    override val host = Host.IOS_DEVICE_APP
    override val kind = BindingKind.Live
    override val reaches = setOf(BackgroundTimeState.TIME_RUNS_OUT)

    override fun create(state: BackgroundTimeState, clauseId: String): Entered<BackgroundTime> {
        // A state this host does not present in this run: the app's time remaining runs live on the simulator app.
        if (state !in reaches) {
            return Entered.Unreachable(
                "this run records the app's time running out; $state runs live",
            )
        }
        recorder.open(clauseId)
        return Entered.Ready(
            IosBackgroundTime(
                Logger.withTag("contract"),
                RecordingBackgroundTimeApi(SystemBackgroundTimeApi, recorder),
            ),
        )
    }
}

/** How long a person has to go to the home screen once the run is asked for. */
private val BACKGROUND_WINDOW = 2.minutes

private const val POLL_MICROS = 250_000u

/**
 * Runs the background-time contract with the app on the home screen. Asked for with the app in front, the run holds
 * background time itself — unrecorded, so the app is not suspended before the clause's own hold begins — and waits for
 * the person to go to the home screen; the clause's hold then runs until iOS says its time is up, about thirty seconds
 * later. iOS suspends the app soon after: open it again to let the run answer (it is kept in `Documents/contracts/`
 * either way). An app still in front after [BACKGROUND_WINDOW] refuses the run.
 */
internal fun recordBackgroundTimeExpiry(): String = whileHoldingBackgroundTime("contract.BackgroundTime.run") {
    val started = TimeSource.Monotonic.markNow()
    while (!appInBackground() && started.elapsedNow() < BACKGROUND_WINDOW) usleep(POLL_MICROS)
    if (!appInBackground()) {
        CONTRACT_REFUSED + "the app stayed in front for $BACKGROUND_WINDOW: go to the home screen right after asking.\n"
    } else {
        recordAppOnDevice(BackgroundTimeContract, null) { DeviceBackgroundTimeBinding(it) }
    }
}
