package app.snapsync.model

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.ResourceRole
import kotlinx.serialization.Serializable

/**
 * An event's present members, how many of them have settled what they share, and — [waiting] — how many are still
 * sharing: not settled, or with a declared photo that has not arrived. The member count and the ended event's waiting
 * line. [waiting] is `null` from a backend, or a record, that predates it.
 */
@Serializable
data class MemberCounts(val active: Int, val settled: Int, val waiting: Int? = null) {
    /** How many present members the event is still waiting for to finish sharing. */
    val waitingFor: Int get() = waiting ?: (active - settled).coerceAtLeast(0)
}

/** One resource a device has stored (`GET /files/devices/<id>`), in the terms the backend addresses it by. */
data class DeviceFile(val assetId: AssetId, val role: ResourceRole, val filename: String)
