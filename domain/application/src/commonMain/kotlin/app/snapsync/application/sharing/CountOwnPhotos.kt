package app.snapsync.application.sharing

import app.snapsync.application.EntryPointCall
import app.snapsync.data.membership.MembershipRead
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.receiving.ReceivedPhotosRead
import app.snapsync.data.receiving.RecognitionRead
import app.snapsync.data.sharing.AdmittedPhotos
import app.snapsync.data.sharing.Contribution
import app.snapsync.data.sharing.DiscoveryRead
import app.snapsync.data.sharing.OwnPhotos
import app.snapsync.domain.membership.Membership
import app.snapsync.inbound.Log
import app.snapsync.inbound.of
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private val log = Log("CountOwnPhotos")

/**
 * **Count the member's own photos the membership admits** — the status's total `N` — and hold them in [admitted]. It
 * reads the photos exactly as the upload does ([OwnPhotos.discover] under the membership's one contribution), so the
 * total never counts a photo the upload would not send: a rule applied there and not here pegs the screen below 100%.
 *
 * Nothing is counted while no membership is held, and nothing while the membership's received photos are not
 * recognised yet: until they are, any of them could count as the member's own, and the upload withholds until then
 * too. A membership that shares nothing counts its zero at once, with nothing read. A library that cannot be read is
 * no count. In each case [admitted] keeps what it last held — never a count not computed, never one withdrawn: "could
 * not count" must never settle the screen as "counted nothing".
 */
class CountOwnPhotos(
    private val membership: MembershipRepository,
    private val received: ReceivedPhotosRead,
    private val photos: OwnPhotos,
    private val admitted: AdmittedPhotos,
    private val time: TimeSource = TimeSource.Monotonic,
) {
    @EntryPointCall
    suspend operator fun invoke(): OwnPhotosCount = when (val read = membership.state.value) {
        is MembershipRead.Joined -> contributing(read.membership)
        is MembershipRead.NotJoined, is MembershipRead.Unavailable -> OwnPhotosCount.NO_MEMBERSHIP
    }

    /** A membership that shares nothing counts its zero at once: no received photo can be mistaken for its own. */
    private suspend fun contributing(joined: Membership): OwnPhotosCount {
        val contribution = photos.contribution(joined)
        return when (contribution.policy.contributes) {
            false -> counted(contribution)
            true -> recognised(joined, contribution)
        }
    }

    private suspend fun recognised(joined: Membership, contribution: Contribution): OwnPhotosCount =
        when (received.recognition(joined.event.id)) {
            is RecognitionRead.Recognised -> counted(contribution)
            RecognitionRead.NotRecognised, is RecognitionRead.Unavailable -> OwnPhotosCount.NOT_RECOGNISED.also {
                log.i("received photos not recognised yet — the total is not counted")
            }
        }

    private suspend fun counted(contribution: Contribution): OwnPhotosCount {
        val started = time.markNow()
        return when (val read = photos.discover(contribution)) {
            is DiscoveryRead.Discovered -> held(read, started)
            DiscoveryRead.NotReadable -> OwnPhotosCount.NOT_READABLE.also {
                log.w("the library cannot be read — the total stays", "held" of heldTotal())
            }
        }
    }

    private suspend fun held(read: DiscoveryRead.Discovered, started: TimeMark): OwnPhotosCount {
        val ids = read.discovery.admitted.mapTo(mutableSetOf()) { photo -> photo.facts.assetId }
        admitted.counted(ids)
        log.i(
            "counted the member's photos",
            "admitted" of ids.size,
            "millis" of started.elapsedNow().inWholeMilliseconds,
        )
        return OwnPhotosCount.COUNTED
    }

    /** The total as held, as a log line says it: its size, or that none was ever counted. */
    private fun heldTotal(): String = when (val held = admitted.state.value) {
        null -> "uncounted"
        else -> "${held.size}"
    }
}

/** What a count came to. Only [COUNTED] changed the total. */
enum class OwnPhotosCount { COUNTED, NO_MEMBERSHIP, NOT_RECOGNISED, NOT_READABLE }
