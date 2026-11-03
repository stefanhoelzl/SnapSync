package app.snapsync.data.wake

import app.snapsync.domain.wake.HeartbeatCadence
import app.snapsync.domain.wake.HeartbeatNetwork
import app.snapsync.domain.wake.HeartbeatTrigger
import app.snapsync.domain.wake.LibraryWatch
import app.snapsync.inbound.Log
import app.snapsync.inbound.of
import app.snapsync.ports.ScheduleResult
import app.snapsync.ports.Wake
import app.snapsync.ports.WakeCadence
import app.snapsync.ports.WakeId
import app.snapsync.ports.WakeNetwork
import app.snapsync.ports.WakeTrigger
import kotlin.time.Duration.Companion.seconds

private val log = Log("Heartbeat")

/** The most a library-change wake may lag the change that prompted it. */
private val LIBRARY_CHANGE_DELAY = 60.seconds

/**
 * The wakes the app asks the operating system for, over the thin [Wake] port: **the heartbeat** — the timed background
 * wake that keeps the process tail running while the app is closed — and the **library-change wake**. Each request is
 * one-shot on every platform and idempotent: a repeated one replaces the pending request rather than stacking one, and
 * a heartbeat at one cadence replaces a pending one at the other.
 */
class WakeArming(private val wake: Wake) {

    /** Request the heartbeat with [trigger]. */
    suspend fun arm(trigger: HeartbeatTrigger): WakeRequest =
        requested(WakeId.Heartbeat, WakeTrigger.After(trigger.earliest, trigger.network.port(), trigger.cadence.port()))
            .also {
                // The field's only record of the cadence a device keeps (`changes/timely-background-receiving`).
                log.i(
                    "heartbeat requested",
                    "cadence" of trigger.cadence,
                    "earliest" of trigger.earliest,
                    "network" of trigger.network,
                    "answer" of it,
                )
            }

    /**
     * Request the library-change wake. A platform without it — iOS, whose library-change wake is the upload extension
     * — answers it unsupported: not a failure, and no watch stands.
     */
    suspend fun watchLibrary(): LibraryWatch = when (
        requested(WakeId.LibraryChanged, WakeTrigger.LibraryChange(maxDelay = LIBRARY_CHANGE_DELAY))
    ) {
        WakeRequest.Standing -> LibraryWatch.STANDING
        WakeRequest.Unsupported, is WakeRequest.Refused -> LibraryWatch.NONE
    }

    /** Withdraw every pending wake (a leave, or a disarm). */
    fun cancelAll() {
        WakeId.entries.forEach(wake::cancel)
    }

    private suspend fun requested(id: WakeId, trigger: WakeTrigger): WakeRequest = when (
        val answer = wake.schedule(
            id,
            trigger,
        )
    ) {
        ScheduleResult.Scheduled -> WakeRequest.Standing
        ScheduleResult.Unsupported -> WakeRequest.Unsupported
        // Not silent: a refused heartbeat is a device that uploads only while the app is open or a push wakes it.
        is ScheduleResult.Refused -> WakeRequest.Refused(answer.detail).also {
            log.w("the operating system refused a wake", "wake" of id, "detail" of answer.detail)
        }
    }

    private fun HeartbeatNetwork.port(): WakeNetwork = when (this) {
        HeartbeatNetwork.ANY -> WakeNetwork.ANY
        HeartbeatNetwork.UNRESTRICTED -> WakeNetwork.UNRESTRICTED
    }

    private fun HeartbeatCadence.port(): WakeCadence = when (this) {
        HeartbeatCadence.BUSY -> WakeCadence.BUSY
        HeartbeatCadence.IDLE -> WakeCadence.IDLE
    }
}

/** What the operating system said to a wake request. */
sealed interface WakeRequest {
    data object Standing : WakeRequest

    /** This platform has no such wake — not a failure. */
    data object Unsupported : WakeRequest

    data class Refused(val detail: String) : WakeRequest
}
