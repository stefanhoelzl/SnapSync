package app.snapsync.format

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.program.InvalidProgramState

/**
 * The app's id for an asset the platform names — one reversible encoding into the canonical form, so [platform] finds
 * the asset again. `/` becomes `_`, and `~` escapes what would otherwise be ambiguous or not canonical: `~~` is `~`,
 * `~_` is `_`, `~.` is a `.` right after another (no id holds `..`), and `~` with four hex digits is any other
 * character. It is the identity on every id stored before it existed: a PhotoKit `localIdentifier`
 * (`{UUID}/L0/NNN`) holds nothing but letters, digits, `-` and `/`, whose `/` always became `_`, and a media-store id
 * is decimal.
 */
object AssetIdText {

    /** The app's id of the asset the platform calls [platformId]. */
    fun fromPlatform(platformId: String): AssetId {
        if (platformId.isEmpty()) throw InvalidProgramState("the platform names every asset it hands out")
        val encoded = StringBuilder()
        var previous: Char? = null
        for (c in platformId) {
            encoded.append(encode(c, previous))
            previous = c
        }
        return AssetId.parse(encoded.toString()) ?: throw InvalidProgramState("the encoding emits only canonical characters")
    }

    /** The platform's id of the asset the app calls [id]: [fromPlatform] read backwards. */
    fun platform(id: AssetId): String {
        val text = id.value
        val decoded = StringBuilder()
        var at = 0
        while (at < text.length) {
            val c = text[at]
            when {
                c == SLASH -> decoded.append('/')
                c != ESCAPE -> decoded.append(c)
                text[at + 1] in ESCAPED -> decoded.append(text[++at])
                else -> {
                    decoded.append(text.substring(at + 1, at + 1 + CODE_DIGITS).toInt(HEX).toChar())
                    at += CODE_DIGITS
                }
            }
            at++
        }
        return decoded.toString()
    }

    private fun encode(c: Char, previous: Char?): String = when {
        c == '/' -> SLASH.toString()
        c == ESCAPE || c == SLASH -> "$ESCAPE$c"
        c == '.' && previous == '.' -> "$ESCAPE$c"
        c == '.' || c == '-' || c.isAsciiLetterOrDigit() -> c.toString()
        else -> ESCAPE + c.code.toString(HEX).padStart(CODE_DIGITS, '0')
    }

    private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9'

    private const val ESCAPE = '~'
    private const val SLASH = '_'
    private const val ESCAPED = "~_."
    private const val HEX = 16
    private const val CODE_DIGITS = 4
}
