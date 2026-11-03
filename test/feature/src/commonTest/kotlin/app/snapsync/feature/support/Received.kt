@file:OptIn(MintsRecognised::class)

package app.snapsync.feature.support

import app.snapsync.data.receiving.HandlesRead
import app.snapsync.data.receiving.ReceivedPhotosRead
import app.snapsync.data.receiving.ReceivedReadiness
import app.snapsync.data.receiving.RecognitionRead
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.event.EventId
import app.snapsync.domain.receiving.MintsRecognised
import app.snapsync.domain.receiving.Recognised

/**
 * What this device received through SnapSync, scripted: whether the store can be read, the received photos' library
 * assets ([handles], `null` for a store that cannot say), and whether a membership's received photos are recognised.
 * It counts what was asked, because what a reader must NOT ask is as much the point as what it answers.
 */
class TestReceived(
    var readiness: ReceivedReadiness = ReceivedReadiness.Ready,
    var handles: Set<AssetId>? = emptySet(),
    var recognised: Boolean = true,
) : ReceivedPhotosRead {
    var readinessAsked = 0
    var handlesAsked = 0

    override suspend fun readiness(): ReceivedReadiness = readiness.also { readinessAsked++ }

    override suspend fun handles(): HandlesRead {
        handlesAsked++
        return handles?.let { HandlesRead.Ok(it) } ?: HandlesRead.Unavailable("the download store is locked")
    }

    override suspend fun recognition(event: EventId): RecognitionRead = when (recognised) {
        true -> RecognitionRead.Recognised(Recognised(event))
        false -> RecognitionRead.NotRecognised
    }
}
