package app.snapsync.feature.creation

import app.snapsync.model.CreateEventPayload
import app.snapsync.model.EventLinkPayload
import app.snapsync.model.encodeEventUrl
import app.snapsync.ports.CreateOutcome
import app.snapsync.ports.EventCreation
import co.touchlab.kermit.Logger

/**
 * The **headless** create-event use-case (capability `ios-app-shell`, `SNAPSYNC_CREATE_EVENT`): mint an
 * event via the backend, then either join it or report its id — with no UI, so the interactive create
 * flow's tap-gated pending-join is bypassed. It exists so the on-device dev loop can create events over
 * USB; the *interactive* create path ([CreateEvent]) is unchanged.
 *
 * It owns the `CreateOutcome` branch deliberately, so the untested `:app:ios` shell stays thin wiring
 * (the outcome `when` is under test here, in `commonTest`, on both JVM and `iosSimulatorArm64`). Effects
 * are injected as lambdas ([forwardAutoJoinLink]), the pattern the `flow/` zone uses — this use-case
 * touches only the [EventCreation] port and pure `model/` helpers.
 *
 * On [CreateOutcome.Created]:
 * - **autoJoin** → forward a **synthesized** `autoJoin` [EventLinkPayload] link (carrying the minted id
 *   plus any supplied cutoff/direction/album) through [forwardAutoJoinLink] — the shell wires that to the
 *   existing `onOpenUrl`/join-gate `autoConfirm` path, so the whole tested join machinery is reused and
 *   the cutoff is clamped to the join floor there like every other path;
 * - **mint-only** → log `created eventId=<uuid>`, the greppable headless oracle for the minted id.
 *
 * On failure, it logs the reason and forwards nothing. It is **non-idempotent** by nature — each call
 * mints a fresh backend event — which is the trigger's honest contract, not this class's concern.
 *
 * [now] supplies the canonical `…Z` "now" used when the payload carries no `startsAt`; it is injected so
 * the default is testable off any clock.
 */
class HeadlessCreate(
    private val client: EventCreation,
    private val log: Logger,
    private val now: () -> String,
) {
    suspend fun run(payload: CreateEventPayload, forwardAutoJoinLink: (String) -> Unit) {
        val startsAt = payload.startsAt ?: now()
        when (val outcome = client.create(payload.name.trim(), startsAt)) {
            is CreateOutcome.Created -> {
                if (payload.autoJoin) {
                    forwardAutoJoinLink(
                        encodeEventUrl(
                            EventLinkPayload(
                                eventId = outcome.eventId,
                                autoJoin = true,
                                minPhotoDate = payload.minPhotoDate,
                                direction = payload.direction,
                                saveToAlbum = payload.saveToAlbum,
                            ),
                        ),
                    )
                } else {
                    // The headless oracle: the mint-only id, greppable in debug.log.
                    log.i { "created eventId=${outcome.eventId}" }
                }
            }
            CreateOutcome.InvalidName -> log.i { "headless create rejected: invalid name" }
            CreateOutcome.Transient -> log.i { "headless create failed: transient/server error" }
        }
    }
}
