package app.snapsync.format

import app.snapsync.domain.event.EventId
import app.snapsync.domain.invite.InviteKey
import app.snapsync.domain.invite.InviteLink
import app.snapsync.domain.invite.InviteLinkVerdict
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64

/**
 * An invite link's text — the one codec: the app shares what [format] writes and opens what
 * [parse] reads, so the two cannot drift. Two forms are read, and every later version keeps reading both, since a
 * printed code opens forever:
 *
 * - the **fragment form** `<origin>/join#v=3&d=<base64url(json)>`, the JSON holding the event id — what a plain event's
 *   invite is written as, since every installed build reads it;
 * - the **path form** `<origin>/join/<eventId>`, an encrypted event's key in its fragment as `#k=<key>` — what a
 *   browser sends the server, so the event page can name the event (`changes/server-rendered-event-page`).
 *
 * Links once carried development hints (`autoJoin`, `minPhotoDate`, `maxPhotoDate`, `direction`, `saveToAlbum`). A
 * link that still does is read as the plain invite it is, its hints ignored: nothing a link says joins without a tap.
 *
 * The origin is matched strictly, as one prefix, and the text is never handed to a URL type: everything after `#` is
 * one opaque fragment, the shape a structured parser gets wrong.
 */
object InviteLinkText {

    /** The fragment form's version: `3` is the payload holding only the event id; older ones are refused. */
    const val VERSION: Int = 3

    private const val FRAGMENT_PREFIX = "$LINK_ORIGIN/join#"
    private const val PATH_PREFIX = "$LINK_ORIGIN/join/"
    private const val KEY = "k"

    private const val EVENT_ID = "eventId"

    /** The keys a fragment form's JSON may hold: the event id, and the hints an older link may carry, ignored. */
    private val PAYLOAD_KEYS = setOf(EVENT_ID, "autoJoin", "minPhotoDate", "maxPhotoDate", "direction", "saveToAlbum")

    private val encoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    private val decoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

    /** [link] as the app shares it: an encrypted event's in the path form with its key, a plain one's in the fragment form. */
    fun format(link: InviteLink): String = when (val key = link.key) {
        null -> {
            val payload = buildJsonObject { put(EVENT_ID, link.event.toString()) }.toString()
            "${FRAGMENT_PREFIX}v=$VERSION&d=${encoder.encode(payload.encodeToByteArray())}"
        }
        else -> "$PATH_PREFIX${link.event}#$KEY=${key.text}"
    }

    /** What [raw] is: an invite, an invite whose key cannot be read, or no invite. Never throws. */
    fun parse(raw: String): InviteLinkVerdict {
        val text = raw.trim()
        return when {
            text.startsWith(PATH_PREFIX) -> pathForm(text.removePrefix(PATH_PREFIX))
            text.startsWith(FRAGMENT_PREFIX) ->
                fragmentForm(text.removePrefix(FRAGMENT_PREFIX))?.let { InviteLinkVerdict.Invite(InviteLink(it, null)) }
                    ?: InviteLinkVerdict.NotAnInvite
            else -> InviteLinkVerdict.NotAnInvite
        }
    }

    /**
     * The invite a Google Play install carried, or `null` when it carried none. The event page's Play button hands Play
     * the fragment form's payload — `v=3&d=…`, and an encrypted event's `&k=<key>` — which Play answers the installed
     * app URL-decoded once. `null` is every other install, above all the organic one, whose referrer Play fills in
     * itself: it must stop here, or every ordinary install would open on a damaged invite.
     */
    fun fromInstallReferrer(referrer: String): InviteLink? {
        val fields = referrer.trim().split("&")
        val keyField = fields.singleOrNull { it.startsWith("$KEY=") }
        val key = keyField?.removePrefix("$KEY=")
        val event = fragmentForm(fields.filterNot { it == keyField }.joinToString("&")) ?: return null
        return when (key) {
            null -> InviteLink(event, null)
            else -> EventKeyText.decode(key)?.let { InviteLink(event, InviteKey(key)) }
        }
    }

    /**
     * After `<origin>/join/`: the event id (a trailing `/` tolerated), then an optional `#` fragment of `key=value`
     * pairs. Once the event id reads, a fragment that cannot hold a readable key — cut short, malformed — is an invite
     * whose key cannot be read, never "no invite".
     */
    private fun pathForm(rest: String): InviteLinkVerdict {
        val path = rest.substringBefore('#')
        val event = EventId.parse(path.removeSuffix("/")) ?: return InviteLinkVerdict.NotAnInvite
        val fragment = rest.substringAfter('#', missingDelimiterValue = "")
        if (fragment.isEmpty()) return InviteLinkVerdict.Invite(InviteLink(event, null))
        val pairs = pairs(fragment) ?: return InviteLinkVerdict.KeyUnreadable(event)
        val key = pairs[KEY] ?: return InviteLinkVerdict.Invite(InviteLink(event, null))
        return when (EventKeyText.decode(key)) {
            null -> InviteLinkVerdict.KeyUnreadable(event)
            else -> InviteLinkVerdict.Invite(InviteLink(event, InviteKey(key)))
        }
    }

    /** After `<origin>/join#`: `v=3&d=<base64url(json)>` — the event id it names, or `null` when it names none. */
    private fun fragmentForm(fragment: String): EventId? {
        val pairs = pairs(fragment) ?: return null
        if (pairs["v"] != VERSION.toString()) return null
        val bytes = pairs["d"]?.let { d ->
            try {
                decoder.decode(d)
            } catch (_: IllegalArgumentException) {
                null
            }
        } ?: return null
        val payload = try {
            Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return null
        if (!PAYLOAD_KEYS.containsAll(payload.keys)) return null
        val eventId = payload[EVENT_ID] as? JsonPrimitive ?: return null
        return eventId.takeIf { it.isString }?.let { EventId.parse(it.content) }
    }

    /** A fragment's `key=value` pairs, or `null` when it is not made of them. */
    private fun pairs(fragment: String): Map<String, String>? = fragment.split("&").associate { part ->
        val eq = part.indexOf('=')
        if (eq <= 0) return null
        part.substring(0, eq) to part.substring(eq + 1)
    }
}
