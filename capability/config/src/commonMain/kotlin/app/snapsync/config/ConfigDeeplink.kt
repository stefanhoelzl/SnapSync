@file:OptIn(ExperimentalEncodingApi::class)

package app.snapsync.config

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The `snapsync://config?v=2&d=<base64url(json)>` wire format (design.md D1/D2): the runtime config
 * payload — bucket, region, and credentials (secret included) — carried in a single opaque,
 * versioned param. The upload **host** is not here: it is fixed at compile time by the extension's
 * `BackgroundUploadURLBase`. This file is the one authoritative codec: the QR generator encodes with
 * [encodeConfigUrl] and the app decodes with [decodeConfigUrl], so the format cannot drift between
 * producer and consumer. [S3ConfigPayload] is the wire DTO (its property names are the JSON keys).
 */

const val CONFIG_SCHEME: String = "snapsync"
const val CONFIG_HOST: String = "config"

/** Bumped to `2` for the four-key payload (host removed). A stale `v=1` QR is rejected, not mis-read. */
const val CONFIG_VERSION: Int = 2

private const val PREFIX = "$CONFIG_SCHEME://$CONFIG_HOST?"

/** Strict by default: unknown or missing keys are a parse failure, never a silent partial. */
private val json = Json { ignoreUnknownKeys = false; isLenient = false }

/** Base64url without padding for output; decoding accepts padding or not. */
private val encoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
private val decoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

/** The outcome of decoding a deeplink: a valid payload, or a typed reason it was rejected. */
sealed interface ConfigDecodeResult {
    data class Success(val payload: S3ConfigPayload) : ConfigDecodeResult
    data class Failure(val reason: String) : ConfigDecodeResult
}

/** Encodes a payload into its canonical deeplink URL. The inverse of [decodeConfigUrl]. */
fun encodeConfigUrl(payload: S3ConfigPayload): String {
    val payloadJson = json.encodeToString(S3ConfigPayload.serializer(), payload)
    val d = encoder.encode(payloadJson.encodeToByteArray())
    return "$PREFIX" + "v=$CONFIG_VERSION&d=$d"
}

/**
 * Decodes a raw `snapsync://` URL into an [S3ConfigPayload], performing structural-only validation
 * (scheme/host, `v == 2`, base64url, UTF-8 JSON, all four keys present and non-empty, no stray
 * keys) and **no** network I/O. Never throws: every deviation becomes a [ConfigDecodeResult.Failure].
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
        json.decodeFromString(S3ConfigPayload.serializer(), jsonBytes.decodeToString())
    } catch (_: SerializationException) {
        return fail("payload is not valid config JSON")
    } catch (_: IllegalArgumentException) {
        return fail("payload is not valid config JSON")
    }

    if (payload.bucket.isEmpty() || payload.region.isEmpty() ||
        payload.accessKeyId.isEmpty() || payload.secretAccessKey.isEmpty()
    ) {
        return fail("config has one or more empty fields")
    }

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
