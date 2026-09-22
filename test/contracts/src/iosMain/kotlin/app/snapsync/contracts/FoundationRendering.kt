@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.snapsync.contracts

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSNumber
import platform.Foundation.create
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.numberWithDouble
import platform.Foundation.numberWithLongLong
import platform.Foundation.timeIntervalSince1970
import platform.posix.memcpy

/**
 * Renders an operating-system dictionary — the attributes of a query, or of an answer — as one line of
 * `key=value` tokens, sorted by key so the same dictionary always renders the same way (capability
 * `port-contracts`, "Replay matches exactly, in order, over deterministic clauses").
 *
 * Values carry a type tag so an answer parses back to the objects the adapter reads: `s:` string, `d:`
 * data (hex), `n:` number, `t:` date (seconds since 1970), `o:` anything else (its description — visible
 * in the recording, but replayed as a string). Spaces, `%` and newlines inside values are percent-escaped,
 * so a token never contains the separator.
 */
fun renderAttributes(attributes: Map<*, *>): String =
    attributes.entries
        .map { (k, v) -> "$k" to renderValue(v) }
        .sortedBy { it.first }
        .joinToString(" ") { (k, v) -> "$k=$v" }

private fun renderValue(value: Any?): String = when (value) {
    null -> "o:null"
    is String -> "s:" + escape(value)
    is NSData -> "d:" + value.toByteArray().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    is NSDate -> "t:" + value.timeIntervalSince1970.toString()
    is NSNumber -> "n:" + value.stringValue
    is Boolean -> "n:" + (if (value) "1" else "0")
    is Number -> "n:$value"
    else -> "o:" + escape(value.toString())
}

/**
 * The inverse of [renderAttributes] for an answer, producing Foundation objects: `String`, [NSData],
 * [NSNumber], [NSDate]. A masked value (`<masked>`) and an `o:` value come back as plain strings.
 */
fun parseAttributes(rendered: String): Map<String, Any> =
    if (rendered.isBlank()) {
        emptyMap()
    } else {
        rendered.split(' ').associate { token ->
            val eq = token.indexOf('=')
            require(eq > 0) { "not a key=value token: $token" }
            token.substring(0, eq) to parseValue(token.substring(eq + 1))
        }
    }

private fun parseValue(raw: String): Any = when {
    raw.startsWith("s:") -> unescape(raw.substring(2))
    raw.startsWith("d:") -> raw.substring(2).chunked(2).map { it.toInt(16).toByte() }.toByteArray().toNSData()
    raw.startsWith("n:") -> raw.substring(2).let { n ->
        if ('.' in n) NSNumber.numberWithDouble(n.toDouble()) else NSNumber.numberWithLongLong(n.toLong())
    }
    raw.startsWith("t:") -> raw.substring(2).toDoubleOrNull()?.let { NSDate.dateWithTimeIntervalSince1970(it) } ?: raw
    raw.startsWith("o:") -> unescape(raw.substring(2))
    else -> raw
}

private fun escape(s: String) = s.replace("%", "%25").replace(" ", "%20").replace("\n", "%0A")

private fun unescape(s: String) = s.replace("%0A", "\n").replace("%20", " ").replace("%25", "%")

private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    return ByteArray(size).also { out -> out.usePinned { memcpy(it.addressOf(0), bytes, length) } }
}
