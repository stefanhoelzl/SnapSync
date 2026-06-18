@file:OptIn(ExperimentalEncodingApi::class)

package app.snapsync.config

import app.snapsync.s3.S3Config
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The `snapsync://config?v=1&d=<base64url(json)>` wire format (design.md D1/D2): the whole
 * [S3Config] — secret included — carried in a single opaque, versioned param. This file is the one
 * authoritative codec: the QR generator encodes with [encodeConfigUrl] and the app decodes with
 * [decodeConfigUrl], so the format cannot drift between producer and consumer.
 */

const val CONFIG_SCHEME: String = "snapsync"
const val CONFIG_HOST: String = "config"
const val CONFIG_VERSION: Int = 1

private const val PREFIX = "$CONFIG_SCHEME://$CONFIG_HOST?"

/** Strict by default: unknown or missing keys are a parse failure, never a silent partial. */
private val json = Json { ignoreUnknownKeys = false; isLenient = false }

/** Base64url without padding for output; decoding accepts padding or not. */
private val encoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
private val decoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

@Serializable
private class ConfigPayload(
    val bucket: String,
    val region: String,
    val endpoint: String,
    val accessKeyId: String,
    val secretAccessKey: String,
)

/** The outcome of decoding a deeplink: a valid config, or a typed reason it was rejected. */
sealed interface ConfigDecodeResult {
    data class Success(val config: S3Config) : ConfigDecodeResult
    data class Failure(val reason: String) : ConfigDecodeResult
}

/** Encodes a config into its canonical deeplink URL. The inverse of [decodeConfigUrl]. */
fun encodeConfigUrl(config: S3Config): String {
    val payloadJson = json.encodeToString(
        ConfigPayload.serializer(),
        ConfigPayload(
            bucket = config.bucket,
            region = config.region,
            endpoint = config.endpoint,
            accessKeyId = config.accessKeyId,
            secretAccessKey = config.secretAccessKey,
        ),
    )
    val d = encoder.encode(payloadJson.encodeToByteArray())
    return "$PREFIX" + "v=$CONFIG_VERSION&d=$d"
}

/**
 * Decodes a raw `snapsync://` URL into an [S3Config], performing structural-only validation
 * (scheme/host, `v == 1`, base64url, UTF-8 JSON, all five keys non-empty) and **no** network I/O.
 * Never throws: every deviation becomes a [ConfigDecodeResult.Failure].
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
        json.decodeFromString(ConfigPayload.serializer(), jsonBytes.decodeToString())
    } catch (_: SerializationException) {
        return fail("payload is not valid config JSON")
    } catch (_: IllegalArgumentException) {
        return fail("payload is not valid config JSON")
    }

    if (payload.bucket.isEmpty() || payload.region.isEmpty() || payload.endpoint.isEmpty() ||
        payload.accessKeyId.isEmpty() || payload.secretAccessKey.isEmpty()
    ) {
        return fail("config has one or more empty fields")
    }

    return ConfigDecodeResult.Success(
        S3Config(
            bucket = payload.bucket,
            region = payload.region,
            endpoint = payload.endpoint,
            accessKeyId = payload.accessKeyId,
            secretAccessKey = payload.secretAccessKey,
        ),
    )
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
