@file:OptIn(ExperimentalEncodingApi::class)

package app.snapsync.config

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The `https://<domain>/join#v=3&d=<base64url(json)>` wire format (spec: event-link): the runtime config
 * payload — just the **event id** — carried in a single opaque, versioned param. The device holds no
 * storage credential; the event id is the upload capability. The upload **host** is not here: it is
 * fixed at compile time by the extension's `BackgroundUploadURLBase`. This file is the one
 * authoritative codec: the QR generator encodes with [encodeEventUrl] and the app decodes with
 * [decodeEventUrl], so the format cannot drift between producer and consumer. [EventLinkPayload]
 * is the wire DTO (its property name is the JSON key).
 *
 * The payload rides in the **fragment**, never the query string, and that is load-bearing rather than
 * cosmetic. A browser never transmits the fragment, so when an invite is opened *without* the app — the
 * only case that reaches our infrastructure at all — the backend sees exactly `GET /join` and the event
 * id stays off the wire, out of the CDN's logs, and out of any cache key. Under the retired `snapsync://`
 * scheme that property was free (no server could observe a custom-scheme URL); under a Universal Link it
 * is bought here. Moving the payload to `?` would look like tidying and would silently forfeit it.
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

/** Canonical UUID (`8-4-4-4-12` hex, case-insensitive). The edge endpoint validates `eventId` likewise. */
private val UUID_REGEX =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/** Strict by default: unknown or missing keys are a parse failure, never a silent partial. */
private val json = Json { ignoreUnknownKeys = false; isLenient = false }

/** Base64url without padding for output; decoding accepts padding or not. */
private val encoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
private val decoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

/** The outcome of decoding an event link: a valid payload, or a typed reason it was rejected. */
sealed interface ConfigDecodeResult {
    data class Success(val payload: EventLinkPayload) : ConfigDecodeResult
    data class Failure(val reason: String) : ConfigDecodeResult
}

/** Encodes a payload into its canonical event-link URL. The inverse of [decodeEventUrl]. */
fun encodeEventUrl(payload: EventLinkPayload): String {
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
    if (!trimmed.startsWith(PREFIX)) return fail("not a snapsync event link")

    val params = parseFragment(trimmed.substring(PREFIX.length)) ?: return fail("malformed fragment")

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
    } catch (_: SerializationException) {
        return fail("payload is not valid config JSON")
    } catch (_: IllegalArgumentException) {
        return fail("payload is not valid config JSON")
    }

    if (payload.eventId.isEmpty()) return fail("config has an empty eventId")
    if (!UUID_REGEX.matches(payload.eventId)) return fail("eventId is not a canonical UUID")

    val dir = payload.direction
    if (dir != null && Direction.fromWire(dir) == null) return fail("unknown direction: $dir")

    return ConfigDecodeResult.Success(payload)
}

private fun fail(reason: String) = ConfigDecodeResult.Failure(reason)

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
