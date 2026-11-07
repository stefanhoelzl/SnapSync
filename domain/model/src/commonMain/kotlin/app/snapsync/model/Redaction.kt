package app.snapsync.model

/**
 * Redacts every encrypted event's key an invite link in [text] carries — any `k=` starting a word (`#k=…`, `&k=…`, a
 * pasted fragment). Unlike an id, a key is never worth keeping: it opens every photo of its event.
 */
fun redactEventKeys(text: String): String =
    if (text.contains("k=")) EVENT_KEY_IN_LINK.replace(text, "$1$REDACTED_KEY") else text

const val REDACTED_KEY: String = "‹key›"

private val EVENT_KEY_IN_LINK = Regex("(\\bk=)[A-Za-z0-9_-]{43}")
