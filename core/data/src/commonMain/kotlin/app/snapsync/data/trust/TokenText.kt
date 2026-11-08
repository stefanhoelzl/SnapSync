package app.snapsync.data.trust

import app.snapsync.domain.time.Timestamp
import app.snapsync.domain.trust.BearerToken
import kotlin.time.Instant

/**
 * A token's text, `<deviceId>.<expiry>.<signature>` — **signed, not encrypted**, so the device reads its own expiry
 * with no key and no network. The one place the format is read.
 */
object TokenText {
    /** The token [text] spells, with the expiry it carries — none for text that is not one of ours. */
    fun parse(text: String): BearerToken = BearerToken(
        text,
        text.split(".").getOrNull(1)?.toLongOrNull()?.let { Timestamp.of(Instant.fromEpochSeconds(it)) },
    )
}
