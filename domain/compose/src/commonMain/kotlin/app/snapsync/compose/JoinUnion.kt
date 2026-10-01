package app.snapsync.compose

import app.snapsync.model.UnionAsset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/**
 * The event union one provision has already read, handed from the membership entry's adoption to the provision's
 * download reconcile (capability `receiving-photos`), so a join reads the union once. Both run inside one provision,
 * seconds apart, and ask the same question: a second read would only repeat the answer.
 *
 * Held only [during] a provision, and only for the event it provisions: outside one, an offer is dropped and a take
 * answers `null`, so no later trigger — a push, an opening — ever plans from a union it did not read. A take answers
 * the union once. Nothing offered (the adoption's read failed or timed out, or a re-provision of the joined event ran
 * no adoption) leaves the reconcile to read its own.
 */
internal class JoinUnion {
    private class Held(val eventId: String, val assets: List<UnionAsset>? = null)

    private val held = MutableStateFlow<Held?>(null)

    suspend fun <T> during(eventId: String, provision: suspend () -> T): T {
        held.value = Held(eventId)
        try {
            return provision()
        } finally {
            held.value = null
        }
    }

    fun offer(eventId: String, assets: List<UnionAsset>) =
        held.update { current -> if (current?.eventId == eventId) Held(eventId, assets) else current }

    fun take(eventId: String): List<UnionAsset>? =
        held.getAndUpdate { current -> if (current?.eventId == eventId) Held(eventId) else current }
            ?.takeIf { it.eventId == eventId }?.assets
}
