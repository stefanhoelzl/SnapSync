@file:OptIn(ExperimentalEncodingApi::class)

package app.snapsync.config

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The `snapsync://config?v=3&d=<base64url(json)>` wire format (design.md D1/D2): the runtime config
 * payload — just the **event id** — carried in a single opaque, versioned param. The device holds no
 * storage credential; the event id is the upload capability. The upload **host** is not here: it is
 * fixed at compile time by the extension's `BackgroundUploadURLBase`. This file is the one
 * authoritative codec: the QR generator encodes with [encodeConfigUrl] and the app decodes with
 * [decodeConfigUrl], so the format cannot drift between producer and consumer. [EventLinkPayload]
 * is the wire DTO (its property name is the JSON key).
 */

const val CONFIG_SCHEME: String = "snapsync"
const val CONFIG_HOST: String = "config"

/**
 * Bumped to `3` for the single-key `{eventId}` payload (the v1/v2 S3 credential payloads are gone).
 * A stale `v=1`/`v=2` QR is rejected, not mis-read — so an upgraded device falls through to the
 * "not joined" setup gate and the user rescans the new event QR.
 */
const val CONFIG_VERSION: Int = 3

private const val PREFIX = "$CONFIG_SCHEME://$CONFIG_HOST?"

/** Canonical UUID (`8-4-4-4-12` hex, case-insensitive). The edge endpoint validates `eventId` likewise. */
private val UUID_REGEX =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/** Strict by default: unknown or missing keys are a parse failure, never a silent partial. */
private val json = Json { ignoreUnknownKeys = false; isLenient = false }

/** Base64url without padding for output; decoding accepts padding or not. */
private val encoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
private val decoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

/** The outcome of decoding a deeplink: a valid payload, or a typed reason it was rejected. */
sealed interface ConfigDecodeResult {
    data class Success(val payload: EventLinkPayload) : ConfigDecodeResult
    data class Failure(val reason: String) : ConfigDecodeResult
}

/** Encodes a payload into its canonical deeplink URL. The inverse of [decodeConfigUrl]. */
fun encodeConfigUrl(payload: EventLinkPayload): String {
    val payloadJson = json.encodeToString(EventLinkPayload.serializer(), payload)
    val d = encoder.encode(payloadJson.encodeToByteArray())
    return "$PREFIX" + "v=$CONFIG_VERSION&d=$d"
}

/**
 * Decodes a raw `snapsync://` URL into an [EventLinkPayload], performing structural-only validation
 * (scheme/host, `v == 3`, base64url, UTF-8 JSON with the required `eventId` key plus the optional
 * `autoJoin`/`minPhotoDate` keys and no other, `eventId` non-empty and a canonical UUID) and **no**
 * network I/O. Never throws: every deviation becomes a [ConfigDecodeResult.Failure]. The success result
 * carries the decoded [EventLinkPayload.autoJoin] (default `false`) and [EventLinkPayload.minPhotoDate]
 * (default `null`). The strict serializer (`ignoreUnknownKeys = false`) still rejects any *other* key.
 */
fun decodeConfigUrl(raw: String): ConfigDecodeResult {
    val trimmed = raw.trim()
    if (!trimmed.startsWith(PREFIX)) return fail("not a snapsync config link")

    val params = parseQuery(trimmed.substring(PREFIX.length)) ?: return fail("malformed query")

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

    return ConfigDecodeResult.Success(payload)
}

private fun fail(reason: String) = ConfigDecodeResult.Failure(reason)

private fun parseQuery(query: String): Map<String, String>? {
    if (query.isEmpty()) return null
    val map = mutableMapOf<String, String>()
    for (part in query.split("&")) {
        val eq = part.indexOf('=')
        if (eq <= 0) return null
        map[part.substring(0, eq)] = part.substring(eq + 1)
    }
    return map
}
