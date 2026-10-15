package app.snapsync.format

import kotlin.io.encoding.Base64

/**
 * An encrypted event's key as text — in an invite link, in the secure store, in a file's headers: base64url without
 * padding, of exactly [KEY_BYTES] bytes. One spelling per key, so a key and its text are one-to-one.
 */
object EventKeyText {
    /** An AES-256 key's length. */
    const val KEY_BYTES: Int = 32

    private val encoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    private val decoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

    /** [key]'s text. */
    fun encode(key: ByteArray): String = encoder.encode(key)

    /** The key [text] spells, or `null` when it spells none — another length, padding, or not base64url at all. */
    fun decode(text: String): ByteArray? = try {
        decoder.decode(text).takeIf { it.size == KEY_BYTES && encoder.encode(it) == text }
    } catch (_: IllegalArgumentException) {
        null
    }
}
