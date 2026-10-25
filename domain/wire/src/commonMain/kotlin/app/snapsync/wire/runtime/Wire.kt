package app.snapsync.wire.runtime

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json

/**
 * One request of the device API, as the generated routes (`app.snapsync.wire.api.Routes`) build it: [path] relative to
 * the API's base, its query included; the [headers] the operation itself declares; a JSON [body] or none. The token
 * and the version header are not here — the gateway adds them, and only it.
 */
class WireRequest(val method: String, val path: String, val headers: Map<String, String>, val body: String?)

/** One answer, as the transport carried it: its status, its headers (looked up case-insensitively) and its text. */
class WireResponse(val status: Int, headers: Map<String, String>, val body: String) {
    private val headers = headers.mapKeys { it.key.lowercase() }

    /** The header [name], or `null` — header names are case-insensitive (RFC 9110). */
    fun header(name: String): String? = headers[name.lowercase()]

    /** Whether the answer is of media [type], parameters (`; charset=…`) aside. */
    fun isMedia(type: String): Boolean {
        val declared = header("Content-Type") ?: return false
        return declared.substringBefore(';').trim().equals(type, ignoreCase = true)
    }

    /** The body decoded by [strategy] — or, when it does not decode, [otherwise] of why. */
    inline fun <T> json(strategy: DeserializationStrategy<T>, otherwise: (detail: String) -> Nothing): T = try {
        WireJson.decodeFromString(strategy, body)
    } catch (e: IllegalArgumentException) {
        // kotlinx.serialization's decoding failures are all IllegalArgumentExceptions (SerializationException too).
        otherwise(e.toString())
    }
}

/**
 * The one JSON configuration of the wire. Unknown keys are ignored — a newer backend may add a field — and an absent
 * optional property is `null` rather than a failure; a `null` value is omitted when encoding, which the description
 * reads as absent.
 */
val WireJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** [value] as one path segment: every character outside RFC 3986's unreserved set percent-encoded, as UTF-8. */
fun encodeSegment(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val c = byte.toInt().toChar()
        if (byte >= 0 && (c.isLetterOrDigit() || c in UNRESERVED)) append(c) else append(percent(byte))
    }
}

/** The query string of [parameters] — `?a=1&b=2`, only those present — or `""` when none is. */
fun query(vararg parameters: Pair<String, String?>): String {
    val present = parameters.mapNotNull { (name, value) -> value?.let { name to it } }
    if (present.isEmpty()) return ""
    return present.joinToString("&", prefix = "?") { (name, value) -> "${encodeSegment(name)}=${encodeSegment(value)}" }
}

private const val UNRESERVED = "-._~"
private const val HEX = "0123456789ABCDEF"

private fun percent(byte: Byte): String {
    val b = byte.toInt() and 0xFF
    return "%" + HEX[b shr 4] + HEX[b and 0x0F]
}
