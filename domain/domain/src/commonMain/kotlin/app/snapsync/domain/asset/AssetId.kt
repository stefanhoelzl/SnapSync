package app.snapsync.domain.asset

import kotlin.jvm.JvmInline

/**
 * An asset's identity in its **canonical** form — the one form every part of the system speaks: the core
 * stores, compares and hands it back without ever parsing it, the ledger and the download store key on it,
 * the manifest carries it, and the backend validates and stores it as it arrives (no translation on either
 * side of the wire).
 *
 * **The rule** ([isCanonical]): non-empty, only RFC 3986 *unreserved* characters (`A–Z a–z 0–9 - . _ ~`),
 * and never `..`. That makes it a single URL path segment the backend's `validateFilename` accepts
 * (`api/src/validators.ts`) with an encoded spelling identical to the decoded one (the ledger's recorded
 * `destinationPath` relies on that), and free of the `\n` the download task tag joins its fields on.
 * [parse] is the one way in, so a non-canonical id is unrepresentable.
 *
 * **One reversible encoding mints it from the platform's own id**, in the gallery services — the core finds the
 * asset again by decoding it. PhotoKit's `localIdentifier` (`{UUID}/L0/NNN`) becomes `{UUID}_L0_NNN`, and a decimal
 * media-store id is itself.
 *
 * [toString] is the canonical string itself, so an id interpolates into a key or a log line unchanged.
 */
@JvmInline
value class AssetId private constructor(val value: String) : Comparable<AssetId> {
    override fun compareTo(other: AssetId): Int = value.compareTo(other.value)

    override fun toString(): String = value

    companion object {
        private val CANONICAL = Regex("""(?!.*\.\.)[A-Za-z0-9._~-]+""")

        /** Whether [value] obeys the canonical asset-id rule (see [AssetId]). */
        fun isCanonical(value: String): Boolean = CANONICAL.matches(value)

        /** The asset id [text] spells, or `null` when it is not canonical. */
        fun parse(text: String): AssetId? = if (isCanonical(text)) AssetId(text) else null
    }
}
