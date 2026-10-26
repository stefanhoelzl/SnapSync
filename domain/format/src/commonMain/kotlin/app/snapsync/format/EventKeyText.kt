package app.snapsync.format

import app.snapsync.domain.event.EventKey
import app.snapsync.domain.event.KeyMaterial
import app.snapsync.domain.event.MakesEventKey
import kotlin.io.encoding.Base64

/**
 * An encrypted event's key as text — in an invite link, in the secure store, in a file's headers: base64url without
 * padding, of exactly [EventKey.LENGTH] bytes. One spelling per key, so a key and its text are one-to-one.
 */
@OptIn(KeyMaterial::class, MakesEventKey::class)
object EventKeyText {
    private val encoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    private val decoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

    /** [key]'s text. */
    fun encode(key: EventKey): String = encoder.encode(key.bytes())

    /** The key [text] spells, or `null` when it spells none — another length, padding, or not base64url at all. */
    fun decode(text: String): EventKey? = try {
        decoder.decode(text).takeIf { encoder.encode(it) == text }?.let(EventKey::of)
    } catch (_: IllegalArgumentException) {
        null
    }
}
