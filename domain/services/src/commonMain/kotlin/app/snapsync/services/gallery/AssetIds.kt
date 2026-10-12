package app.snapsync.services.gallery

import app.snapsync.model.AssetId
import app.snapsync.ports.PlatformAssetId

/**
 * The app's id for the asset the platform calls [this] — one reversible encoding into the canonical form, so
 * [platformId] finds the asset again. `/` becomes `_`, and `~` escapes what would otherwise be ambiguous or not
 * canonical: `~~` is `~`, `~_` is `_`, `~.` is a `.` right after another (no id holds `..`), and `~` with four hex
 * digits is any other character. It is the identity on every id stored before it existed: a PhotoKit
 * `localIdentifier` (`{UUID}/L0/NNN`) holds nothing but letters, digits, `-` and `/`, whose `/` always became `_`,
 * and a media-store id is decimal.
 */
fun PlatformAssetId.assetId(): AssetId {
    require(value.isNotEmpty()) { "the platform handed out an empty asset id" }
    val encoded = StringBuilder()
    var previous: Char? = null
    for (c in value) {
        when {
            c == '/' -> encoded.append(SLASH)
            c == ESCAPE || c == SLASH -> encoded.append(ESCAPE).append(c)
            c == '.' && previous == '.' -> encoded.append(ESCAPE).append(c)
            c == '.' || c == '-' || c.isAsciiLetterOrDigit() -> encoded.append(c)
            else -> encoded.append(ESCAPE).append(c.code.toString(HEX).padStart(CODE_DIGITS, '0'))
        }
        previous = c
    }
    return AssetId(encoded.toString())
}

/** The platform's id of the asset the app calls [this]: [assetId] read backwards. */
fun AssetId.platformId(): PlatformAssetId {
    val decoded = StringBuilder()
    var at = 0
    while (at < value.length) {
        val c = value[at]
        when {
            c == SLASH -> decoded.append('/')
            c != ESCAPE -> decoded.append(c)
            value[at + 1] in ESCAPED -> decoded.append(value[++at])
            else -> {
                decoded.append(value.substring(at + 1, at + 1 + CODE_DIGITS).toInt(HEX).toChar())
                at += CODE_DIGITS
            }
        }
        at++
    }
    return PlatformAssetId(decoded.toString())
}

private const val ESCAPE = '~'
private const val SLASH = '_'
private const val ESCAPED = "~_."
private const val HEX = 16
private const val CODE_DIGITS = 4

private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'
