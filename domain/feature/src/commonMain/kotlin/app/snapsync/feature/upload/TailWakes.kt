package app.snapsync.feature.upload

import app.snapsync.model.GalleryAccess
import app.snapsync.model.contained
import app.snapsync.services.config.ConfigService
import app.snapsync.services.upload.ExtensionRegistration
import app.snapsync.services.wake.WakeHold
import co.touchlab.kermit.Logger

/**
 * Hand [trigger]'s tail to [runner] under this hold, and end the hold once it has ended (capability `sync-status`; see
 * [WakeHold]). Only a wake whose tail covered the whole pass runs the end-of-wake [finish] after it — a freed upload
 * slot or a single staged import is too narrow a moment to spend a request on the event's state.
 */
suspend fun WakeHold.thenTail(trigger: TailTrigger, runner: TailRunner, finish: suspend (TailTrigger) -> Unit) =
    thenTail(
        description = "$trigger",
        tail = { runner.request(trigger) },
        finish = { if (trigger.scope == TailScope.FULL) finish(trigger) },
    )

/**
 * [thenTail] for a wake that joins the tail only when [joins] — a silent push for the active event (see
 * [PushTailGuard]); any other ends its hold at once.
 */
suspend fun WakeHold.thenTailWhen(
    joins: Boolean,
    trigger: TailTrigger,
    runner: TailRunner,
    finish: suspend (TailTrigger) -> Unit,
) = if (joins) thenTail(trigger, runner, finish) else end()

/**
 * The heartbeat wake's run (capability `background-upload`, "The tail runner reimplements the OS scheduler"): it holds
 * no [WakeHold] of its own — the operating system's completion is its grant of time. Its tail, then the end-of-wake
 * step, each contained — the next wake runs both again — then [settling]; nothing at all once the OS's time is already
 * up ([released]), because a stop while no tail runs is a no-op.
 */
suspend fun heartbeatWake(
    label: String,
    released: () -> Boolean,
    runner: TailRunner,
    finish: suspend (TailTrigger) -> Unit,
    log: Logger,
    settling: () -> Unit,
) {
    if (released()) return
    log.contained("$label: its tail failed") { runner.request(TailTrigger.HEARTBEAT) }
    log.contained("$label: the end-of-wake step failed; the next wake runs it again") { finish(TailTrigger.HEARTBEAT) }
    settling()
}

/**
 * What the heartbeat's re-arm reads after a tail (capability `receiving-photos`; decision record
 * `changes/timely-background-receiving`, D1, D3) — read fresh each time. The OS uploader counts as confirmed only when it
 * may be registered here ([osUploaderRegistrable]: a full grant, an OS that carries it, the dev pin not off) **and** the
 * OS's own answer says it is: whether registering is allowed says nothing about whether it happened.
 */
fun cadenceFacts(
    config: ConfigService,
    permission: GalleryAccess,
    osUploaderRegistrable: Boolean,
    registration: ExtensionRegistration,
): CadenceFacts {
    val joined = config.config.value
    return CadenceFacts(
        joined = joined != null,
        ended = joined != null && config.hasEnded(joined),
        shares = joined != null && joined.direction.includesUpload,
        fullGrant = permission == GalleryAccess.GRANTED,
        osUploaderConfirmed = osUploaderRegistrable && registration.isRegistered() == true,
    )
}
