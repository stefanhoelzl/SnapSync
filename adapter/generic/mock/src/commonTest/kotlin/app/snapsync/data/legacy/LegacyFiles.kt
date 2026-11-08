package app.snapsync.data.legacy

import app.snapsync.format.LegacyMembership
import app.snapsync.format.LegacyMembershipText
import app.snapsync.format.TimestampText
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** What an updated device's legacy files hold, written as 0.4 wrote them. */
internal object LegacyFiles {
    /** [membership] in its versioned envelope — `eventconfig.json`'s bytes. */
    fun config(membership: LegacyMembership): ByteArray = buildJsonObject {
        put("v", LegacyMembershipText.VERSION)
        putJsonObject("payload") {
            put("eventId", membership.eventId.value)
            put("name", membership.name)
            put("minPhotoDate", TimestampText.format(membership.minPhotoDate.at))
            put("startsAt", TimestampText.format(membership.startsAt.at))
            put("endsAt", TimestampText.format(membership.endsAt.at))
            put("maxPhotoDate", TimestampText.format(membership.maxPhotoDate.at))
            put("deletesAt", TimestampText.format(membership.deletesAt.at))
            put("direction", membership.direction.name)
            put("saveToAlbum", membership.saveToAlbum)
            membership.members?.let { members ->
                putJsonObject("members") {
                    put("active", members.active)
                    put("settled", members.settled)
                    members.waiting?.let { put("waiting", it) }
                }
            }
            membership.openUntil?.let { put("openUntil", TimestampText.format(it.at)) }
            put("deleted", membership.deleted)
            membership.keyId?.let { put("keyId", it) }
        }
    }.toString().encodeToByteArray()
}
