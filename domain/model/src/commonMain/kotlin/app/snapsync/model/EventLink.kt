@file:OptIn(ExperimentalEncodingApi::class)

package app.snapsync.model

import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * The `https://<domain>/join#v=3&d=<base64url(json)>` wire format (spec: join-event): the runtime config
 * payload — just the **event id** — carried in a single opaque, versioned param. The device holds no
 * storage credential; the event id is the upload capability. The upload **host** is not here: it is
 * fixed at compile time by the extension's baked `uploadBase`. This file is the one
 * authoritative codec: the QR generator encodes with [encodeEventUrl] and the app decodes with
 * [decodeEventUrl], so the format cannot drift between producer and consumer. [EventLinkPayload]
 * is the wire DTO (its property name is the JSON key).
 *
 * TWO FORMS are decoded, and every future version must keep decoding both (a printed QR opens forever):
 * - the **fragment form** `<origin>/join#v=3&d=<payload>` — what [encodeEventUrl] produces and the app shares;
 * - the **path form** `<origin>/join/<eventId>`, with the development hints (`autoJoin`, `minPhotoDate`, …) in an
 *   optional fragment `#autoJoin=true&…` — what a browser sends to the server, so the event page can name the
 *   event in a link preview (`changes/server-rendered-event-page`).
 *
 * The encoder still produces the fragment form: every app older than the path form's decoder rejects it, so the
 * shared form switches only once those are gone (that change's phase 2). The fragment form once kept the event id
 * off every server; that rule was given up for the server-rendered event page — see the change's design.
 */

/**
 * Stays `3` for the single-key `{eventId}` payload (the v1/v2 S3 credential payloads are gone). A stale
 * `v=1`/`v=2` QR is rejected, not mis-read — so an upgraded device falls through to the "not joined"
 * setup gate and the user rescans the new event QR. The migration off `snapsync://config?…` did not bump
 * it: the payload is unchanged, and the URL prefix already tells the forms apart.
 */
const val CONFIG_VERSION: Int = 3

/**
 * The one accepted form. [LINK_ORIGIN] is generated from the `snapsync.domain` Gradle property, so the
 * encoder and decoder are anchored to the same constant the `applinks:` entitlement is built from.
 */
private const val PREFIX = "$LINK_ORIGIN/join#"

/** The path form's prefix: `<origin>/join/<eventId>`, an optional `#<hints>` after it. */
private const val PATH_PREFIX = "$LINK_ORIGIN/join/"

/** Canonical UUID (`8-4-4-4-12` hex, case-insensitive). The edge endpoint validates `eventId` likewise. */
private val UUID_REGEX =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/** Strict by default: unknown or missing keys are a parse failure, never a silent partial. */
private val json = Json {
    ignoreUnknownKeys = false
    isLenient = false
}

/** Base64url without padding for output; decoding accepts padding or not. */
private val encoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
private val decoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

/** The outcome of decoding an event link: a valid payload, or a typed reason it was rejected. */
sealed interface ConfigDecodeResult {
    data class Success(val payload: EventLinkPayload) : ConfigDecodeResult
    data class Failure(val reason: String) : ConfigDecodeResult
}

/**
 * Encodes a payload into its canonical event-link URL. The inverse of [decodeEventUrl]. An ENCRYPTED event's link is
 * the path form, its key the fragment's `k` — the form no build before encryption is asked to open — followed by any
 * development hints the payload carries, as the path form reads them (an invite the app shares carries none); every
 * other link stays the fragment form every installed build reads (phase 2 of the event-site change moves them all).
 */
fun encodeEventUrl(payload: EventLinkPayload): String {
    payload.key?.let { key -> return "$PATH_PREFIX${payload.eventId}#" + (listOf("$KEY_PARAM=$key") + pathHints(payload)).joinToString("&") }
    val payloadJson = json.encodeToString(EventLinkPayload.serializer(), payload)
    val d = encoder.encode(payloadJson.encodeToByteArray())
    return "$PREFIX" + "v=$CONFIG_VERSION&d=$d"
}

/**
 * Decodes a raw event-link URL into an [EventLinkPayload], performing structural-only validation
 * (origin and path, `v == 3`, base64url, UTF-8 JSON with the required `eventId` key plus the optional
 * `autoJoin`/`minPhotoDate`/`direction`/`saveToAlbum` keys and no other, `eventId` non-empty and a
 * canonical UUID, and `direction` — when present — one of the known [Direction.wire] tokens) and **no**
 * network I/O. Never throws: every deviation becomes a [ConfigDecodeResult.Failure]. The success result
 * carries the decoded [EventLinkPayload.autoJoin] (default `false`), [EventLinkPayload.minPhotoDate]
 * (default `null`), [EventLinkPayload.direction] (default `null`), and [EventLinkPayload.saveToAlbum]
 * (default `null`). The strict serializer (`ignoreUnknownKeys = false`) still rejects any *other* key.
 *
 * The origin is matched **strictly**, as one prefix, and the URL is deliberately never handed to a
 * structured URL type: everything after `#` is one opaque fragment string, which is exactly the shape a
 * `URLComponents`-style parser gets wrong. A foreign origin cannot reach here in production anyway —
 * `onOpenURL` fires only for a domain our own entitlement names, and the launch-env trigger needs a
 * developer launch — so strict matching is chosen for being *less* code than searching for the path
 * inside an arbitrary string, not as a security control.
 */
fun decodeEventUrl(raw: String): ConfigDecodeResult {
    val trimmed = raw.trim()
    return when {
        trimmed.startsWith(PATH_PREFIX) -> decodePathForm(trimmed.substring(PATH_PREFIX.length))
        trimmed.startsWith(PREFIX) -> decodeFragmentForm(trimmed.substring(PREFIX.length))
        else -> fail("not a snapsync event link")
    }
}

/** The fragment form after `<origin>/join#`: `v=3&d=<base64url(json)>`. */
private fun decodeFragmentForm(fragment: String): ConfigDecodeResult {
    val params = parseFragment(fragment) ?: return fail("malformed fragment")

    val version = params["v"] ?: return fail("missing version")
    if (version != CONFIG_VERSION.toString()) return fail("unsupported version: $version")

    val d = params["d"] ?: return fail("missing payload")
    val jsonBytes = try {
        decoder.decode(d)
    } catch (_: IllegalArgumentException) {
        return fail("payload is not valid base64url")
    }

    val payload = try {
        json.decodeFromString(EventLinkPayload.serializer(), jsonBytes.decodeToString())
    } catch (_: IllegalArgumentException) {
        // `SerializationException` is an `IllegalArgumentException`: every way the payload fails to decode lands here.
        return fail("payload is not valid config JSON")
    }

    if (payload.eventId.isEmpty()) return fail("config has an empty eventId")
    if (!UUID_REGEX.matches(payload.eventId)) return fail("eventId is not a canonical UUID")

    val dir = payload.direction
    if (dir != null && Direction.fromWire(dir) == null) return fail("unknown direction: $dir")

    return ConfigDecodeResult.Success(payload)
}

/**
 * The invite a Google Play install carried, as the event link it came from — or `null` when the install carried none.
 * The event page's Play button hands the invite's fragment payload, exactly `v=3&d=…` and for an encrypted event
 * `&k=<key>`, to Play as the install referrer; Play answers it to the installed app URL-decoded once, so [referrer] is
 * that payload.
 *
 * `null` is every install that did not come through an invite's page — above all the ORGANIC one, whose referrer Play
 * fills in itself (`utm_source=google-play&utm_medium=organic`) — and anything that does not decode as an invite. It
 * must stop here rather than reach the Links port, where an unparsable link is reported as a damaged invite: every
 * ordinary install would open on that error. A decoded invite is answered in its canonical form, so it opens exactly as
 * the tapped link does.
 */
fun inviteLinkFromInstallReferrer(referrer: String): String? {
    // An encrypted event's page appends its key (`&k=`, checked against the event before it does): the invite it
    // carries is then the event's whole one, key included.
    val fields = referrer.trim().split("&")
    val keyField = fields.singleOrNull { it.startsWith("$KEY_PARAM=") }
    val key = keyField?.removePrefix("$KEY_PARAM=")
    if (key != null && decodeEventKey(key) == null) return null
    val payload = fields.filterNot { it == keyField }.joinToString("&")
    return when (val decoded = decodeEventUrl(PREFIX + payload)) {
        is ConfigDecodeResult.Success -> encodeEventUrl(decoded.payload.withKey(key))
        is ConfigDecodeResult.Failure -> null
    }
}

/** This payload carrying [key], or as it is when there is none. */
private fun EventLinkPayload.withKey(key: String?): EventLinkPayload =
    key?.let { EventLinkPayload(eventId, autoJoin, minPhotoDate, maxPhotoDate, direction, saveToAlbum, it) } ?: this

private fun fail(reason: String) = ConfigDecodeResult.Failure(reason)

/**
 * The path form after `<origin>/join/`: the event id (a trailing `/` tolerated), then an optional `#` with the
 * development hints as `key=value` pairs — the same keys, values and strictness as the fragment form's JSON payload:
 * an unknown key or a malformed value fails rather than being dropped. No query is part of the form.
 */
private fun decodePathForm(rest: String): ConfigDecodeResult {
    val hash = rest.indexOf('#')
    val path = if (hash < 0) rest else rest.substring(0, hash)
    val eventId = path.removeSuffix("/")
    if (eventId.isEmpty()) return fail("config has an empty eventId")
    if (!UUID_REGEX.matches(eventId)) return fail("eventId is not a canonical UUID")
    val hints = if (hash < 0 || hash == rest.length - 1) {
        emptyMap()
    } else {
        parseFragment(rest.substring(hash + 1)) ?: return fail("malformed fragment")
    }
    val unknown = hints.keys - PATH_HINT_KEYS - KEY_PARAM
    if (unknown.isNotEmpty()) return fail("unknown hint: ${unknown.first()}")
    val key = hints[KEY_PARAM]
    if (key != null && decodeEventKey(key) == null) return fail("k is not a 32-byte base64url key")
    val autoJoin = hints["autoJoin"]?.let { it.toBooleanStrictOrNull() ?: return fail("autoJoin is not a boolean") }
    val saveToAlbum = hints["saveToAlbum"]?.let {
        it.toBooleanStrictOrNull() ?: return fail("saveToAlbum is not a boolean")
    }
    val dir = hints["direction"]
    if (dir != null && Direction.fromWire(dir) == null) return fail("unknown direction: $dir")
    return ConfigDecodeResult.Success(
        EventLinkPayload(
            eventId = eventId,
            autoJoin = autoJoin ?: false,
            minPhotoDate = hints["minPhotoDate"],
            maxPhotoDate = hints["maxPhotoDate"],
            direction = dir,
            saveToAlbum = saveToAlbum,
            key = key,
        ),
    )
}

/** An encrypted event's key in a link: base64url, no padding. */
fun encodeEventKey(key: ByteArray): String = encoder.encode(key)

/** The 32-byte key [text] names, or `null` when it is anything else. */
fun decodeEventKey(text: String): ByteArray? = try {
    decoder.decode(text).takeIf { it.size == EncryptedFileFormat.KEY_LENGTH && encoder.encode(it) == text }
} catch (_: IllegalArgumentException) {
    null
}

/** [payload]'s development hints as the path form carries them: only those it sets, in [PATH_HINT_KEYS]' order. */
private fun pathHints(payload: EventLinkPayload): List<String> = listOfNotNull(
    "autoJoin=true".takeIf { payload.autoJoin },
    payload.minPhotoDate?.let { "minPhotoDate=$it" },
    payload.maxPhotoDate?.let { "maxPhotoDate=$it" },
    payload.direction?.let { "direction=$it" },
    payload.saveToAlbum?.let { "saveToAlbum=$it" },
)

/** The fragment key an encrypted event's link carries its key in. */
private const val KEY_PARAM = "k"

/** The development hints a path-form link may carry: exactly [EventLinkPayload]'s optional keys. */
private val PATH_HINT_KEYS = setOf("autoJoin", "minPhotoDate", "maxPhotoDate", "direction", "saveToAlbum")

/**
 * Absence: null means "this fragment is not a link payload" — malformed, empty, and unrecognised are
 * one answer because the decoder's caller shows the same invalid-link error for all of them. Pure
 * string work over an in-memory value: there is no read that could fail separately.
 */
private fun parseFragment(fragment: String): Map<String, String>? {
    if (fragment.isEmpty()) return null
    val map = mutableMapOf<String, String>()
    for (part in fragment.split("&")) {
        val eq = part.indexOf('=')
        if (eq <= 0) return null
        map[part.substring(0, eq)] = part.substring(eq + 1)
    }
    return map
}
